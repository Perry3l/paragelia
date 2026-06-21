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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
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

public class TakeAwayOrdersActivity extends BaseActivity {

    private RecyclerView rvOrders;
    private TakeAwayAdapter adapter;
    private List<OrderData> orderList = new ArrayList<>();
    private DatabaseReference takeawayRef;

    private PaymentManager paymentManager;
    private DriverManager mDriverManager;
    private Sys mSys;
    private Printer mPrinter;

    private double pendingAmount = 0.0;
    private String pendingOrderNumber = "";
    private String pendingOrderId = "";
    private List<Map<String, Object>> pendingItems;
    private java.util.concurrent.ExecutorService printExecutor;
    private PrinterManager printerManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_takeaway_orders);
        showMemoryOverlay();
        rvOrders = findViewById(R.id.rvTakeAwayOrders);
        rvOrders.setLayoutManager(new LinearLayoutManager(this));
        takeawayRef = FirebaseHelper.getReference("takeaway_orders");

        printerManager = PrinterManager.getInstance(this);
        printerManager.loadPrintersConfig();

        SharedPreferences prefs = getSharedPreferences("my_prefs", MODE_PRIVATE);
        String jwt = prefs.getString("jwt", null);
        if (jwt == null) {
            performEpsilonLogin();
        } else {
            initializePrinterAndLoadOrders();
        }
        paymentManager = new PaymentManager(this);
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
        printExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
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

        double total = 0.0;
        if (order.items != null) {
            for (Map<String, Object> item : order.items) {
                double price = item.get("price") instanceof Number ? ((Number) item.get("price")).doubleValue() : 0.0;
                int qty = item.get("quantity") instanceof Number ? ((Number) item.get("quantity")).intValue() : 1;
                total += price * qty;
            }
        }
        final double finalTotal = total;

        final String orderNumber = order.orderNumber;
        final String orderId = order.id;
        final List<Map<String, Object>> items = order.items;

        String[] options = {"💳 Πληρωμή με Κάρτα", "💵 Πληρωμή με Μετρητά"};
        new AlertDialog.Builder(this)
                .setTitle("Επιλογή Τρόπου Πληρωμής")
                .setItems(options, (dialog, which) -> {

                    pendingOrderNumber = orderNumber;
                    pendingOrderId = orderId;
                    pendingItems = items;
                    pendingAmount = finalTotal;

                    if (which == 0) {
                        paymentManager.startPosPayment(finalTotal, orderNumber,
                                "Take Away " + orderNumber, items,
                                new PaymentManager.PaymentCallback() {
                                    @Override
                                    public void onSuccess(com.ads.paragelia.paroxos.SendResponse response) {

                                        String markStr = String.valueOf(response.getMark());
                                        String uid = response.getUid() != null ? response.getUid() : "-";
                                        String authCode = response.getAuthenticationCode() != null ? response.getAuthenticationCode() : "-";
                                        String qrUrl = response.getQrCode() != null ? response.getQrCode() : "";

                                        printTakeAwayOrder(pendingOrderNumber, pendingItems, markStr, uid, authCode, qrUrl);
                                        takeawayRef.child(pendingOrderId).removeValue();
                                        Toast.makeText(TakeAwayOrdersActivity.this, "Η πληρωμή ολοκληρώθηκε", Toast.LENGTH_SHORT).show();
                                    }
                                    @Override
                                    public void onError(String message) {
                                        Toast.makeText(TakeAwayOrdersActivity.this, "Σφάλμα: " + message, Toast.LENGTH_SHORT).show();
                                    }
                                });
                    } else {
                        paymentManager.startCashPayment(finalTotal, orderNumber,
                                "Take Away " + orderNumber, items,
                                new PaymentManager.PaymentCallback() {
                                    @Override
                                    public void onSuccess(com.ads.paragelia.paroxos.SendResponse response) {

                                        String markStr = String.valueOf(response.getMark());
                                        String uid = response.getUid() != null ? response.getUid() : "-";
                                        String authCode = response.getAuthenticationCode() != null ? response.getAuthenticationCode() : "-";
                                        String qrUrl = response.getQrCode() != null ? response.getQrCode() : "";

                                        printTakeAwayOrder(pendingOrderNumber, pendingItems, markStr, uid, authCode, qrUrl);
                                        takeawayRef.child(pendingOrderId).removeValue();
                                        Toast.makeText(TakeAwayOrdersActivity.this, "Η πληρωμή ολοκληρώθηκε", Toast.LENGTH_SHORT).show();
                                    }
                                    @Override
                                    public void onError(String message) {
                                        Toast.makeText(TakeAwayOrdersActivity.this, "Σφάλμα: " + message, Toast.LENGTH_SHORT).show();
                                    }
                                });
                    }
                })
                .setNegativeButton("Ακύρωση", null)
                .show();
    }



    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001) {
            paymentManager.handlePosResult(resultCode, data);
        }
    }



    private void printTakeAwayOrder(String orderNumber, List<Map<String, Object>> items,
                                    String mark, String uid, String authCode, String qrUrl) {
        runOnUiThread(() -> Toast.makeText(this, "Εκτύπωση...", Toast.LENGTH_SHORT).show());

        printExecutor.execute(() -> {
            int printStatus = mPrinter.getPrinterStatus();
            if (printStatus == SdkResult.SDK_PRN_STATUS_PAPEROUT) {
                runOnUiThread(() -> Toast.makeText(TakeAwayOrdersActivity.this, "Δεν υπάρχει χαρτί", Toast.LENGTH_LONG).show());
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
            format.setAli(Layout.Alignment.ALIGN_NORMAL);
            format.setTextSize(20);
            mPrinter.setPrintAppendString("ΠΑΡΟΧΟΣ: EPSILON DIGITAL", format);
            mPrinter.setPrintAppendString("ΜΑΡΚ: " + mark, format);
            mPrinter.setPrintAppendString("UID: " + uid, format);
            mPrinter.setPrintAppendString("ΚΩΔ. ΑΥΘΕΝΤΙΚΟΠΟΙΗΣΗΣ:", format);
            mPrinter.setPrintAppendString(authCode, format);

            if (qrUrl != null && !qrUrl.isEmpty()) {
                mPrinter.setPrintAppendString("----------------", format);
                try {
                    mPrinter.setPrintAppendQRCode(qrUrl, 200, 200, Layout.Alignment.ALIGN_CENTER);
                } catch (Exception e) {
                    mPrinter.setPrintAppendString("QR URL: " + qrUrl, format);
                }
            }
            format.setTextSize(25);


            format.setAli(Layout.Alignment.ALIGN_CENTER);
            mPrinter.setPrintAppendString("----------------", format);
            mPrinter.setPrintAppendString("Σας ευχαριστούμε!", format);
            mPrinter.setPrintAppendString("\n\n\n", format);

            int ret = mPrinter.setPrintStart();

            runOnUiThread(() -> {
                if (ret == SdkResult.SDK_OK) {
                    Toast.makeText(TakeAwayOrdersActivity.this, "Η απόδειξη εκτυπώθηκε", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(TakeAwayOrdersActivity.this, "Σφάλμα εκτύπωσης: " + ret, Toast.LENGTH_LONG).show();
                }
            });

            if (mPrinter.isSuppoerCutter()) {
                mPrinter.openPrnCutter((byte) 1);
            }
        });
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
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (printExecutor != null) printExecutor.shutdownNow();
    }
}