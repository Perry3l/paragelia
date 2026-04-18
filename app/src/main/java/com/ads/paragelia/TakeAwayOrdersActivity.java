package com.ads.paragelia;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Layout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zcs.sdk.DriverManager;
import com.zcs.sdk.Printer;
import com.zcs.sdk.SdkResult;
import com.zcs.sdk.Sys;
import com.zcs.sdk.print.PrnStrFormat;
import com.zcs.sdk.print.PrnTextFont;
import com.zcs.sdk.print.PrnTextStyle;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class TakeAwayOrdersActivity extends AppCompatActivity {

    private RecyclerView rvOrders;
    private TakeAwayAdapter adapter;
    private List<OrderData> orderList = new ArrayList<>();
    private DatabaseReference takeawayRef;

    // Εκτυπωτής
    private DriverManager mDriverManager;
    private Sys mSys;
    private Printer mPrinter;

    // Πληρωμή
    private double pendingAmount = 0.0;
    private String pendingOrderNumber = "";
    private String pendingOrderId = "";
    private List<Map<String, Object>> pendingItems;
    private PaymentCompleteCallback pendingPaymentCallback;
    private int pendingPaymentType = 7;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_takeaway_orders);

        rvOrders = findViewById(R.id.rvTakeAwayOrders);
        rvOrders.setLayoutManager(new LinearLayoutManager(this));
        takeawayRef = FirebaseDatabase.getInstance().getReference("takeaway_orders");

        // Έλεγχος ύπαρξης JWT token
        SharedPreferences prefs = getSharedPreferences("my_prefs", MODE_PRIVATE);
        String jwt = prefs.getString("jwt", null);
        if (jwt == null) {
            performEpsilonLogin();
        } else {
            initializePrinterAndLoadOrders();
        }
    }

    private void performEpsilonLogin() {
        com.ads.paragelia.paroxos.LoginRequest request = new com.ads.paragelia.paroxos.LoginRequest(
                "1ADB7B09478F4C58892ADDBB23E0EF65",
                "EA824C0CDE234554AFEE",
                "Vvt8UTjQO_TO/CVkAcG9"
        );

        com.ads.paragelia.paroxos.RetrofitClient.getInstance().getApiService()
                .loginToSubscription(request)
                .enqueue(new Callback<com.ads.paragelia.paroxos.LoginResponse>() {
                    @Override
                    public void onResponse(Call<com.ads.paragelia.paroxos.LoginResponse> call,
                                           Response<com.ads.paragelia.paroxos.LoginResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            com.ads.paragelia.paroxos.LoginResponse loginData = response.body();
                            SharedPreferences prefs = getSharedPreferences("my_prefs", MODE_PRIVATE);
                            prefs.edit()
                                    .putString("jwt", loginData.getJwt())
                                    .putString("refreshToken", loginData.getJwtRefreshToken())
                                    .putString("baseUrl", loginData.getUrl1())
                                    .apply();
                            runOnUiThread(() -> initializePrinterAndLoadOrders());
                        } else {
                            runOnUiThread(() -> {
                                Toast.makeText(TakeAwayOrdersActivity.this, "Αποτυχία σύνδεσης με Epsilon", Toast.LENGTH_LONG).show();
                                finish();
                            });
                        }
                    }

                    @Override
                    public void onFailure(Call<com.ads.paragelia.paroxos.LoginResponse> call, Throwable t) {
                        runOnUiThread(() -> {
                            Toast.makeText(TakeAwayOrdersActivity.this, "Σφάλμα δικτύου: " + t.getMessage(), Toast.LENGTH_LONG).show();
                            finish();
                        });
                    }
                });
    }

    private void initializePrinterAndLoadOrders() {
        // Αρχικοποίηση εκτυπωτή
        mDriverManager = DriverManager.getInstance();
        mSys = mDriverManager.getBaseSysDevice();
        mPrinter = mDriverManager.getPrinter();
        int initRet = mSys.sdkInit();
        if (initRet != SdkResult.SDK_OK) {
            mSys.sysPowerOn();
            try { Thread.sleep(1000); } catch (InterruptedException e) {}
            initRet = mSys.sdkInit();
            if (initRet != SdkResult.SDK_OK) {
                Toast.makeText(this, "Αποτυχία αρχικοποίησης εκτυπωτή", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        loadOrders();
    }

    private void loadOrders() {
        takeawayRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                orderList.clear();
                for (DataSnapshot orderSnap : snapshot.getChildren()) {
                    String orderId = orderSnap.getKey();
                    DataSnapshot currentOrderSnap = orderSnap.child("current_order");
                    Map<String, Object> currentOrder = (Map<String, Object>) currentOrderSnap.getValue();
                    if (currentOrder != null) {
                        OrderData order = new OrderData();
                        order.id = orderId;
                        order.orderNumber = (String) currentOrder.get("orderNumber");
                        if (order.orderNumber == null) order.orderNumber = orderId;
                        Object ts = currentOrder.get("timestamp");
                        order.timestamp = ts instanceof Number ? ((Number) ts).longValue() : 0L;
                        order.items = (List<Map<String, Object>>) currentOrder.get("items");
                        orderList.add(order);
                    }
                }
                if (adapter == null) {
                    adapter = new TakeAwayAdapter(orderList);
                    rvOrders.setAdapter(adapter);
                } else {
                    adapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(TakeAwayOrdersActivity.this, "Σφάλμα φόρτωσης", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showPaymentMethodDialog(OrderData order) {
        String[] options = {"💳 Πληρωμή με Κάρτα", "💵 Πληρωμή με Μετρητά"};
        new AlertDialog.Builder(this)
                .setTitle("Επιλογή Τρόπου Πληρωμής")
                .setItems(options, (dialog, which) -> {
                    pendingOrderNumber = order.orderNumber;
                    pendingOrderId = order.id;
                    pendingItems = order.items;
                    // Υπολογισμός συνολικού ποσού
                    double total = 0.0;
                    if (order.items != null) {
                        for (Map<String, Object> item : order.items) {
                            double price = item.get("price") instanceof Number ? ((Number) item.get("price")).doubleValue() : 0.0;
                            int qty = item.get("quantity") instanceof Number ? ((Number) item.get("quantity")).intValue() : 1;
                            total += price * qty;
                        }
                    }
                    pendingAmount = total;

                    if (which == 0) {
                        startPosPayment(total);
                    } else {
                        startCashPayment(total);
                    }
                })
                .setNegativeButton("Ακύρωση", null)
                .show();
    }

    private void startCashPayment(double amount) {
        pendingPaymentType = 3;
        pendingPaymentCallback = () -> {
            printTakeAwayOrder(pendingOrderNumber, pendingItems);
            takeawayRef.child(pendingOrderId).removeValue();
            Toast.makeText(this, "Η πληρωμή ολοκληρώθηκε", Toast.LENGTH_SHORT).show();
        };
        Toast.makeText(this, "Αποστολή στο myDATA...", Toast.LENGTH_SHORT).show();
        sendToEpsilonProvider();
    }

    private void startPosPayment(double amount) {
        pendingPaymentType = 7;
        pendingPaymentCallback = () -> {
            printTakeAwayOrder(pendingOrderNumber, pendingItems);
            takeawayRef.child(pendingOrderId).removeValue();
            Toast.makeText(this, "Η πληρωμή ολοκληρώθηκε", Toast.LENGTH_SHORT).show();
        };
        Toast.makeText(this, "Αναμονή έγκρισης από Epsilon...", Toast.LENGTH_SHORT).show();

        double netAmount = Math.round((amount / 1.13) * 100.0) / 100.0;
        double vatAmount = Math.round((amount - netAmount) * 100.0) / 100.0;

        String orderRef = "TA_" + System.currentTimeMillis();
        String todayDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date());

        JsonObject requestPaymentObj = new JsonObject();
        requestPaymentObj.addProperty("externalSystemId", orderRef);
        requestPaymentObj.addProperty("issuerVatNumber", "000000000");
        requestPaymentObj.addProperty("invoiceIssueDate", todayDate);
        requestPaymentObj.addProperty("companyBranch", "0");
        requestPaymentObj.addProperty("invoiceType", "11.2");
        requestPaymentObj.addProperty("invoiceSeries", "A");
        requestPaymentObj.addProperty("invoiceAA", String.valueOf(System.currentTimeMillis() % 100000));
        requestPaymentObj.addProperty("netValue", netAmount);
        requestPaymentObj.addProperty("vatAmount", vatAmount);
        requestPaymentObj.addProperty("totalValue", amount);
        requestPaymentObj.addProperty("paymentAmount", amount);
        requestPaymentObj.addProperty("terminalId", "22223729");
        requestPaymentObj.addProperty("nspCode", "8");

        SharedPreferences prefs = getSharedPreferences("my_prefs", MODE_PRIVATE);
        String jwtToken = prefs.getString("jwt", null);
        String dynamicBaseUrl = prefs.getString("baseUrl", null);

        if (jwtToken == null || dynamicBaseUrl == null) {
            Toast.makeText(this, "Σφάλμα σύνδεσης με Epsilon", Toast.LENGTH_LONG).show();
            return;
        }
        String fullUrl = dynamicBaseUrl + (dynamicBaseUrl.endsWith("/") ? "" : "/") + "api/requestPayment";

        com.ads.paragelia.paroxos.RetrofitClient.getInstance().getSendService()
                .requestPayment(fullUrl, "Bearer " + jwtToken, "3.0", requestPaymentObj)
                .enqueue(new Callback<JsonObject>() {
                    @Override
                    public void onResponse(Call<JsonObject> call, Response<JsonObject> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            try {
                                JsonObject res = response.body();
                                String uid = res.has("uid") ? res.get("uid").getAsString() : "";
                                JsonObject paymentToken = res.getAsJsonObject("paymentToken");
                                String signature = paymentToken.get("signature").getAsString();
                                String timestamp = paymentToken.get("timestamp").getAsString();

                                Intent posIntent = com.ads.paragelia.paroxos.EpayHelper.createSaleIntent(
                                        getPackageName(), amount, netAmount, vatAmount, orderRef, uid, signature, "004", timestamp, "CONTACTLESS", "22223729"
                                );
                                if (posIntent != null) startActivityForResult(posIntent, 1001);
                            } catch (Exception e) {
                                Toast.makeText(TakeAwayOrdersActivity.this, "Σφάλμα ανάγνωσης PaymentToken", Toast.LENGTH_LONG).show();
                            }
                        }
                    }
                    @Override public void onFailure(Call<JsonObject> call, Throwable t) {}
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && data != null && resultCode == RESULT_OK) {
            try {
                String resultJsonStr = data.getStringExtra("gr.epayworldwide.softpos.RESULT");
                org.json.JSONObject outerJson = new org.json.JSONObject(resultJsonStr);
                org.json.JSONObject innerJson = new org.json.JSONObject(outerJson.getString("payload"));
                if (innerJson.getInt("resultCode") == 0) {
                    sendToEpsilonProvider();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Σφάλμα επεξεργασίας αποτελέσματος POS", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void sendToEpsilonProvider() {
        SharedPreferences prefs = getSharedPreferences("my_prefs", MODE_PRIVATE);
        String jwt = prefs.getString("jwt", null);
        String baseUrl = prefs.getString("baseUrl", null);

        if (jwt == null || baseUrl == null) return;

        double netValue = Math.round((pendingAmount / 1.13) * 100.0) / 100.0;
        double vatAmount = Math.round((pendingAmount - netValue) * 100.0) / 100.0;

        JsonObject sourceObj = new JsonObject();
        JsonObject invoiceObj = new JsonObject();

        JsonObject issuerObj = new JsonObject();
        issuerObj.addProperty("vatNumber", "000000000");
        issuerObj.addProperty("branch", 0);
        issuerObj.addProperty("city", "ΠΟΛΗ");
        issuerObj.addProperty("country", "GR");
        invoiceObj.add("issuer", issuerObj);

        JsonObject counterpartObj = new JsonObject();
        counterpartObj.add("vatNumber", null);
        counterpartObj.addProperty("name", "ΠΕΛΑΤΗΣ ΛΙΑΝΙΚΗΣ");
        counterpartObj.addProperty("country", "GR");
        counterpartObj.addProperty("branch", 0);
        invoiceObj.add("counterpart", counterpartObj);

        JsonObject headerObj = new JsonObject();
        headerObj.addProperty("series", "A");
        headerObj.addProperty("aa", String.valueOf(System.currentTimeMillis() % 100000));
        String todayDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        headerObj.addProperty("issueDate", todayDate);
        headerObj.addProperty("invoiceType", "11.2");
        headerObj.addProperty("currency", "EUR");
        invoiceObj.add("invoiceHeader", headerObj);

        JsonObject line1 = new JsonObject();
        line1.addProperty("lineNumber", 1);
        line1.addProperty("quantity", 1);
        line1.addProperty("entityName", "Take Away " + pendingOrderNumber);
        line1.addProperty("netValue", netValue);
        line1.addProperty("vatCategory", 2);
        line1.addProperty("vatAmount", vatAmount);
        line1.addProperty("vatPercent", 13);
        line1.addProperty("totalValue", pendingAmount);
        line1.addProperty("measurementUnit", 1);
        line1.addProperty("classificationCategory", "category1_3");
        line1.addProperty("classificationType", "E3_561_003");
        JsonArray detailsArray = new JsonArray();
        detailsArray.add(line1);
        invoiceObj.add("invoiceDetails", detailsArray);

        JsonArray paymentsArray = new JsonArray();
        JsonObject payment1 = new JsonObject();
        payment1.addProperty("type", pendingPaymentType);
        payment1.addProperty("amount", pendingAmount);
        paymentsArray.add(payment1);
        invoiceObj.add("paymentMethods", paymentsArray);

        JsonObject summaryObj = new JsonObject();
        summaryObj.addProperty("totalNetValue", netValue);
        summaryObj.addProperty("totalVatAmount", vatAmount);
        summaryObj.addProperty("totalValue", pendingAmount);
        invoiceObj.add("invoiceSummary", summaryObj);

        sourceObj.add("invoice", invoiceObj);

        com.ads.paragelia.paroxos.SendRequest sendRequest = new com.ads.paragelia.paroxos.SendRequest(
                "SYS_" + System.currentTimeMillis(), "eInvoicing", 5, sourceObj
        );

        String fullUrl = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "api/send";
        com.ads.paragelia.paroxos.RetrofitClient.getInstance().getSendService()
                .sendInvoice(fullUrl, "Bearer " + jwt, "3.0", sendRequest)
                .enqueue(new Callback<com.ads.paragelia.paroxos.SendResponse>() {
                    @Override
                    public void onResponse(Call<com.ads.paragelia.paroxos.SendResponse> call,
                                           Response<com.ads.paragelia.paroxos.SendResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            com.ads.paragelia.paroxos.SendResponse res = response.body();
                            if (res.getStatus() == 1 || res.getMark() > 0) {
                                if (pendingPaymentCallback != null) {
                                    pendingPaymentCallback.onComplete();
                                    pendingPaymentCallback = null;
                                }
                            } else if (res.getStatus() == 0) {
                                new android.os.Handler().postDelayed(() ->
                                        checkInvoiceStatus(res.getProcessId(), res.getExternalSystemId()), 1000);
                            }
                        }
                    }
                    @Override public void onFailure(Call<com.ads.paragelia.paroxos.SendResponse> call, Throwable t) {}
                });
    }

    private void checkInvoiceStatus(String processId, String externalSystemId) {
        SharedPreferences prefs = getSharedPreferences("my_prefs", MODE_PRIVATE);
        String jwtToken = prefs.getString("jwt", null);
        String baseUrl = prefs.getString("baseUrl", null);
        if (jwtToken == null || baseUrl == null) return;

        String fullUrl = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "api/get";
        com.ads.paragelia.paroxos.GetStatusRequest statusReq =
                new com.ads.paragelia.paroxos.GetStatusRequest(processId, externalSystemId);

        com.ads.paragelia.paroxos.RetrofitClient.getInstance().getSendService()
                .getInvoiceStatus(fullUrl, "Bearer " + jwtToken, "3.0", statusReq)
                .enqueue(new Callback<com.ads.paragelia.paroxos.SendResponse>() {
                    @Override
                    public void onResponse(Call<com.ads.paragelia.paroxos.SendResponse> call,
                                           Response<com.ads.paragelia.paroxos.SendResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            com.ads.paragelia.paroxos.SendResponse res = response.body();
                            if (res.getStatus() == 1) {
                                if (pendingPaymentCallback != null) {
                                    pendingPaymentCallback.onComplete();
                                    pendingPaymentCallback = null;
                                }
                            } else if (res.getStatus() == 0) {
                                new android.os.Handler().postDelayed(() ->
                                        checkInvoiceStatus(processId, externalSystemId), 1000);
                            }
                        }
                    }
                    @Override public void onFailure(Call<com.ads.paragelia.paroxos.SendResponse> call, Throwable t) {}
                });
    }

    private void printTakeAwayOrder(String orderNumber, List<Map<String, Object>> items) {
        int printStatus = mPrinter.getPrinterStatus();
        if (printStatus == SdkResult.SDK_PRN_STATUS_PAPEROUT) {
            Toast.makeText(this, "Δεν υπάρχει χαρτί", Toast.LENGTH_LONG).show();
            return;
        }

        PrnStrFormat format = new PrnStrFormat();
        format.setTextSize(25);
        format.setStyle(PrnTextStyle.NORMAL);
        format.setFont(PrnTextFont.MONOSPACE);

        format.setAli(Layout.Alignment.ALIGN_CENTER);
        mPrinter.setPrintAppendString("TAKE AWAY", format);
        mPrinter.setPrintAppendString("----------------", format);

        format.setAli(Layout.Alignment.ALIGN_NORMAL);
        mPrinter.setPrintAppendString("Αριθμός: " + orderNumber, format);
        mPrinter.setPrintAppendString("Ημερομηνία: " +
                android.text.format.DateFormat.format("dd/MM/yyyy HH:mm:ss", System.currentTimeMillis()), format);

        format.setAli(Layout.Alignment.ALIGN_CENTER);
        mPrinter.setPrintAppendString("----------------", format);

        format.setAli(Layout.Alignment.ALIGN_NORMAL);
        mPrinter.setPrintAppendString("Προϊόντα:", format);

        if (items != null) {
            for (Map<String, Object> item : items) {
                String name = (String) item.get("name");
                Object qtyObj = item.get("quantity");
                int quantity = (qtyObj instanceof Number) ? ((Number) qtyObj).intValue() : 1;
                String comment = (String) item.get("comment");
                String line = name + "  x" + quantity;
                if (comment != null && !comment.isEmpty()) {
                    line += " (" + comment + ")";
                }
                mPrinter.setPrintAppendString(line, format);
            }
        }

        format.setAli(Layout.Alignment.ALIGN_CENTER);
        mPrinter.setPrintAppendString("----------------", format);
        mPrinter.setPrintAppendString("Σας ευχαριστούμε!", format);
        mPrinter.setPrintAppendString("\n\n\n", format);

        int ret = mPrinter.setPrintStart();
        if (ret == SdkResult.SDK_OK) {
            Toast.makeText(this, "Η απόδειξη εκτυπώθηκε", Toast.LENGTH_SHORT).show();
            if (mPrinter.isSuppoerCutter()) {
                mPrinter.openPrnCutter((byte) 1);
            }
        } else {
            Toast.makeText(this, "Σφάλμα εκτύπωσης: " + ret, Toast.LENGTH_LONG).show();
        }
    }

    private void cancelOrder(OrderData order) {
        new AlertDialog.Builder(this)
                .setTitle("Ακύρωση παραγγελίας")
                .setMessage("Θέλετε να ακυρώσετε την παραγγελία " + order.orderNumber + ";")
                .setPositiveButton("Ναι", (dialog, which) -> {
                    takeawayRef.child(order.id).removeValue()
                            .addOnSuccessListener(aVoid -> Toast.makeText(this, "Η παραγγελία ακυρώθηκε", Toast.LENGTH_SHORT).show())
                            .addOnFailureListener(e -> Toast.makeText(this, "Σφάλμα ακύρωσης", Toast.LENGTH_SHORT).show());
                })
                .setNegativeButton("Όχι", null)
                .show();
    }

    // ==================== Adapter ====================
    private class TakeAwayAdapter extends RecyclerView.Adapter<TakeAwayAdapter.ViewHolder> {
        private List<OrderData> list;

        TakeAwayAdapter(List<OrderData> list) { this.list = list; }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_takeaway_order, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            OrderData order = list.get(position);
            holder.tvOrderNumber.setText("Παραγγελία #" + order.orderNumber);
            if (order.timestamp > 0) {
                String time = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date(order.timestamp));
                holder.tvOrderTime.setText("Ώρα: " + time);
            } else {
                holder.tvOrderTime.setText("");
            }

            StringBuilder itemsStr = new StringBuilder();
            if (order.items != null) {
                for (Map<String, Object> item : order.items) {
                    String name = (String) item.get("name");
                    Object qty = item.get("quantity");
                    itemsStr.append(name).append(" x").append(qty).append("\n");
                }
            }
            holder.tvOrderItems.setText(itemsStr.toString().trim());

            holder.btnPrint.setOnClickListener(v -> showPaymentMethodDialog(order));
            holder.btnCancel.setOnClickListener(v -> cancelOrder(order));
        }

        @Override
        public int getItemCount() { return list.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvOrderNumber, tvOrderTime, tvOrderItems;
            Button btnPrint, btnCancel;
            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvOrderNumber = itemView.findViewById(R.id.tvOrderNumber);
                tvOrderTime = itemView.findViewById(R.id.tvOrderTime);
                tvOrderItems = itemView.findViewById(R.id.tvOrderItems);
                btnPrint = itemView.findViewById(R.id.btnPrint);
                btnCancel = itemView.findViewById(R.id.btnCancel);
            }
        }
    }

    static class OrderData {
        String id;
        String orderNumber;
        long timestamp;
        List<Map<String, Object>> items;
    }

    interface PaymentCompleteCallback {
        void onComplete();
    }
}