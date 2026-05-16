package com.ads.paragelia;

import android.app.AlertDialog;
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
import com.google.firebase.database.FirebaseDatabase;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeliveryOrdersActivity extends BaseActivity {

    private RecyclerView rvOrders;
    private DeliveryAdapter adapter;
    private final List<DeliveryOrder> orderList = new ArrayList<>();
    private DatabaseReference deliveryRef;

    private DriverManager mDriverManager;
    private Sys mSys;
    private Printer mPrinter;
    private ExecutorService printExecutor;
    private PaymentManager paymentManager;

    // Προσωρινά πεδία για την τρέχουσα πληρωμή
    private double pendingAmount;
    private String pendingOrderNumber;
    private String pendingOrderId;
    private List<Map<String, Object>> pendingItems;
    private Map<String, Object> pendingCustomer;
    private static final int SMS_PERMISSION_CODE = 101;
    private String pendingSmsPhone;
    private String pendingSmsMessage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_delivery_orders);
        showMemoryOverlay();
        rvOrders = findViewById(R.id.rvDeliveryOrders);
        rvOrders.setLayoutManager(new LinearLayoutManager(this));
        deliveryRef = FirebaseHelper.getReference("delivery_orders");

        // Αρχικοποίηση PaymentManager
        SharedPreferences prefs = getSharedPreferences("my_prefs", MODE_PRIVATE);
        if (prefs.getString("jwt", null) == null) {
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
                .enqueue(new retrofit2.Callback<com.ads.paragelia.paroxos.LoginResponse>() {
                    @Override
                    public void onResponse(retrofit2.Call<com.ads.paragelia.paroxos.LoginResponse> call,
                                           retrofit2.Response<com.ads.paragelia.paroxos.LoginResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            com.ads.paragelia.paroxos.LoginResponse loginData = response.body();
                            SharedPreferences prefs = getSharedPreferences("my_prefs", MODE_PRIVATE);
                            prefs.edit()
                                    .putString("jwt", loginData.getJwt())
                                    .putString("refreshToken", loginData.getJwtRefreshToken())
                                    .putString("baseUrl", loginData.getUrl1())
                                    .apply();
                            runOnUiThread(() -> initializePrinterAndLoadOrders());
                        }
                    }
                    @Override
                    public void onFailure(retrofit2.Call<com.ads.paragelia.paroxos.LoginResponse> call, Throwable t) {
                        runOnUiThread(() -> {
                            Toast.makeText(DeliveryOrdersActivity.this, "Αποτυχία σύνδεσης με Epsilon", Toast.LENGTH_LONG).show();
                            finish();
                        });
                    }
                });
    }

    private void initializePrinterAndLoadOrders() {
        printExecutor = Executors.newSingleThreadExecutor();
        mDriverManager = DriverManager.getInstance();
        mSys = mDriverManager.getBaseSysDevice();
        mPrinter = mDriverManager.getPrinter();
        int initRet = mSys.sdkInit();
        if (initRet != SdkResult.SDK_OK) {
            mSys.sysPowerOn();
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
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
        deliveryRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                orderList.clear();
                for (DataSnapshot orderSnap : snapshot.getChildren()) {
                    String orderId = orderSnap.getKey();
                    DataSnapshot currentOrderSnap = orderSnap.child("current_order");
                    Map<String, Object> currentOrder = (Map<String, Object>) currentOrderSnap.getValue();
                    if (currentOrder == null) continue;

                    DeliveryOrder order = new DeliveryOrder();
                    order.id = orderId;
                    order.orderNumber = (String) currentOrder.get("orderNumber");
                    if (order.orderNumber == null) order.orderNumber = orderId;
                    Object ts = currentOrder.get("timestamp");
                    order.timestamp = ts instanceof Number ? ((Number) ts).longValue() : 0L;
                    order.items = (List<Map<String, Object>>) currentOrder.get("items");
                    DataSnapshot customerSnap = currentOrderSnap.child("customer");
                    if (customerSnap.exists()) {
                        order.customer = (Map<String, Object>) customerSnap.getValue();
                    }
                    orderList.add(order);
                }
                if (adapter == null) {
                    adapter = new DeliveryAdapter(orderList);
                    rvOrders.setAdapter(adapter);
                } else {
                    adapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) { }
        });
    }

    // ---------- Dialog επιλογής πληρωμής ----------
    private void showPaymentMethodDialog(DeliveryOrder order) {
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
        final Map<String, Object> customer = order.customer;

        String[] options = {"💳 Πληρωμή με Κάρτα", "💵 Πληρωμή με Μετρητά"};
        new AlertDialog.Builder(this)
                .setTitle("Επιλογή Τρόπου Πληρωμής")
                .setItems(options, (dialog, which) -> {
                    pendingOrderNumber = orderNumber;
                    pendingOrderId = orderId;
                    pendingItems = items;
                    pendingCustomer = customer;
                    pendingAmount = finalTotal;

                    if (which == 0) {
                        paymentManager.startPosPayment(finalTotal, orderNumber,
                                "Delivery " + orderNumber, items,
                                new PaymentManager.PaymentCallback() {
                                    @Override
                                    public void onSuccess(com.ads.paragelia.paroxos.SendResponse response) {
                                        String markStr = String.valueOf(response.getMark());
                                        String uid = response.getUid() != null ? response.getUid() : "-";
                                        String authCode = response.getAuthenticationCode() != null ? response.getAuthenticationCode() : "-";
                                        String qrUrl = response.getQrCode() != null ? response.getQrCode() : "";

                                        showReceiptDeliveryDialog(pendingOrderNumber, pendingItems, pendingCustomer,
                                                markStr, uid, authCode, qrUrl);
                                        deliveryRef.child(pendingOrderId).removeValue();
                                        Toast.makeText(DeliveryOrdersActivity.this, "Η πληρωμή ολοκληρώθηκε", Toast.LENGTH_SHORT).show();
                                    }
                                    @Override
                                    public void onError(String message) {
                                        Toast.makeText(DeliveryOrdersActivity.this, "Σφάλμα: " + message, Toast.LENGTH_SHORT).show();
                                    }
                                });
                    } else {
                        paymentManager.startCashPayment(finalTotal, orderNumber,
                                "Delivery " + orderNumber, items,
                                new PaymentManager.PaymentCallback() {
                                    @Override
                                    public void onSuccess(com.ads.paragelia.paroxos.SendResponse response) {
                                        String markStr = String.valueOf(response.getMark());
                                        String uid = response.getUid() != null ? response.getUid() : "-";
                                        String authCode = response.getAuthenticationCode() != null ? response.getAuthenticationCode() : "-";
                                        String qrUrl = response.getQrCode() != null ? response.getQrCode() : "";

                                        showReceiptDeliveryDialog(pendingOrderNumber, pendingItems, pendingCustomer,
                                                markStr, uid, authCode, qrUrl);
                                        deliveryRef.child(pendingOrderId).removeValue();
                                        Toast.makeText(DeliveryOrdersActivity.this, "Η πληρωμή ολοκληρώθηκε", Toast.LENGTH_SHORT).show();
                                    }
                                    @Override
                                    public void onError(String message) {
                                        Toast.makeText(DeliveryOrdersActivity.this, "Σφάλμα: " + message, Toast.LENGTH_SHORT).show();
                                    }
                                });
                    }
                })
                .setNegativeButton("Ακύρωση", null)
                .show();
    }

    // ====================== ΝΕΕΣ ΜΕΘΟΔΟΙ ======================

    private void showReceiptDeliveryDialog(String orderNumber, List<Map<String, Object>> items,
                                           Map<String, Object> customer,
                                           String mark, String uid, String authCode, String qrUrl) {
        // Καθαρίζουμε την παραγγελία από τη βάση (αφού έχει ήδη πληρωθεί)
        deliveryRef.child(pendingOrderId).removeValue();

        // Δημιουργία κειμένου απόδειξης
        String receiptText = buildReceiptText(orderNumber, items, mark, uid, authCode, qrUrl);

        String phone = (customer != null && customer.get("phone") != null) ? customer.get("phone").toString() : "";
        String[] options;
        if (!phone.isEmpty()) {
            options = new String[]{"🖨️ Εκτύπωση απόδειξης", "📱 Αποστολή SMS στο " + phone};
        } else {
            options = new String[]{"🖨️ Εκτύπωση απόδειξης"};
        }

        new AlertDialog.Builder(this)
                .setTitle("Παράδοση απόδειξης")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        // Εκτύπωση
                        printReceiptOnly(orderNumber, items, mark, uid, authCode, qrUrl);
                    } else {
                        // SMS
                        if (!phone.isEmpty()) {
                            sendSmsReceipt(phone, receiptText);
                        }
                    }
                    // Μετά την ολοκλήρωση (ανεξάρτητα από επιλογή), ρωτάμε για το χαρτί του ντελιβερά
                    showDeliveryPaperDialog(customer);
                })
                .show();
    }

    private String buildReceiptText(String orderNumber, List<Map<String, Object>> items,
                                    String mark, String uid, String authCode, String qrUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("DELIVERY #").append(orderNumber).append("\n");
        sb.append("--------------------\n");
        if (items != null) {
            for (Map<String, Object> item : items) {
                String name = (String) item.get("name");
                Object qtyObj = item.get("quantity");
                int qty = (qtyObj instanceof Number) ? ((Number) qtyObj).intValue() : 1;
                sb.append(name).append(" x").append(qty).append("\n");
            }
        }
        sb.append("--------------------\n");
        sb.append("MARK: ").append(mark).append("\n");
        sb.append("UID: ").append(uid).append("\n");
        sb.append("KODIKOS AUTH: ").append(authCode).append("\n");
        if (qrUrl != null && !qrUrl.isEmpty()) {
            sb.append("QR: ").append(qrUrl).append("\n");
        }
        sb.append("Euxaristoume!");
        return sb.toString();
    }

    private void sendSmsReceipt(String phoneNumber, String message) {
        // Έλεγχος αν η συσκευή έχει δυνατότητα SMS
        if (android.telephony.SmsManager.getDefault() == null) {
            Toast.makeText(this, "Η συσκευή δεν υποστηρίζει SMS", Toast.LENGTH_LONG).show();
            return;
        }

        // Έλεγχος άδειας
        if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Αποθήκευση προσωρινά και αίτημα άδειας
            pendingSmsPhone = phoneNumber;
            pendingSmsMessage = message;
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.SEND_SMS}, SMS_PERMISSION_CODE);
        } else {
            // Άδεια ήδη χορηγημένη – απευθείας αποστολή
            sendSmsDirectly(phoneNumber, message);
        }
    }

    private void sendSmsDirectly(String phoneNumber, String message) {
        try {
            android.telephony.SmsManager smsManager = android.telephony.SmsManager.getDefault();
            java.util.ArrayList<String> parts = smsManager.divideMessage(message);
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null);
            Toast.makeText(this, "Το SMS εστάλη στο " + phoneNumber, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Αποτυχία αποστολής SMS: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == SMS_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // Άδεια δόθηκε – συνέχιση αποστολής
                if (pendingSmsPhone != null && pendingSmsMessage != null) {
                    sendSmsDirectly(pendingSmsPhone, pendingSmsMessage);
                    pendingSmsPhone = null;
                    pendingSmsMessage = null;
                }
            } else {
                Toast.makeText(this, "Η άδεια SMS είναι απαραίτητη για την αποστολή", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void printReceiptOnly(String orderNumber, List<Map<String, Object>> items,
                                  String mark, String uid, String authCode, String qrUrl) {
        // Εκτύπωση μόνο του πρώτου χαρτιού (είδη + στοιχεία παρόχου)
        printExecutor.execute(() -> {
            int printStatus = mPrinter.getPrinterStatus();
            if (printStatus == SdkResult.SDK_PRN_STATUS_PAPEROUT) {
                runOnUiThread(() -> Toast.makeText(DeliveryOrdersActivity.this, "Δεν υπάρχει χαρτί", Toast.LENGTH_LONG).show());
                return;
            }

            PrnStrFormat format = new PrnStrFormat();
            format.setTextSize(25);
            format.setStyle(PrnTextStyle.NORMAL);
            format.setFont(PrnTextFont.MONOSPACE);

            format.setAli(Layout.Alignment.ALIGN_CENTER);
            mPrinter.setPrintAppendString("DELIVERY", format);
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

            if (mark != null && !mark.equals("0")) {
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
                    mPrinter.setPrintAppendString("QR URL: " + qrUrl, format);
                }
                format.setTextSize(25);
            }

            format.setAli(Layout.Alignment.ALIGN_CENTER);
            mPrinter.setPrintAppendString("----------------", format);
            mPrinter.setPrintAppendString("\n\n\n", format);

            int ret = mPrinter.setPrintStart();
            if (ret != SdkResult.SDK_OK) {
                runOnUiThread(() -> Toast.makeText(DeliveryOrdersActivity.this, "Σφάλμα εκτύπωσης απόδειξης: " + ret, Toast.LENGTH_LONG).show());
            } else if (mPrinter.isSuppoerCutter()) {
                mPrinter.openPrnCutter((byte) 1);
            }
        });
    }

    private void showDeliveryPaperDialog(Map<String, Object> customer) {
        new AlertDialog.Builder(this)
                .setTitle("Χαρτί για ντελιβερά")
                .setMessage("Να εκτυπωθεί το χαρτί με τα στοιχεία του πελάτη για τον διανομέα;")
                .setPositiveButton("Ναι", (dialog, which) -> printCustomerPaper(customer))
                .setNegativeButton("Όχι", null)
                .show();
    }

    private void printCustomerPaper(Map<String, Object> customer) {
        printExecutor.execute(() -> {
            int printStatus = mPrinter.getPrinterStatus();
            if (printStatus == SdkResult.SDK_PRN_STATUS_PAPEROUT) {
                runOnUiThread(() -> Toast.makeText(DeliveryOrdersActivity.this, "Δεν υπάρχει χαρτί", Toast.LENGTH_LONG).show());
                return;
            }

            PrnStrFormat format = new PrnStrFormat();
            format.setTextSize(20);
            format.setStyle(PrnTextStyle.NORMAL);
            format.setFont(PrnTextFont.MONOSPACE);

            format.setAli(Layout.Alignment.ALIGN_CENTER);
            mPrinter.setPrintAppendString("ΣΤΟΙΧΕΙΑ ΠΕΛΑΤΗ", format);
            mPrinter.setPrintAppendString("----------------", format);

            format.setAli(Layout.Alignment.ALIGN_NORMAL);
            if (customer != null) {
                mPrinter.setPrintAppendString("Όνομα: " + customer.get("name"), format);
                mPrinter.setPrintAppendString("Τηλ.: " + customer.get("phone"), format);
                if (customer.get("address") != null) {
                    mPrinter.setPrintAppendString("Διεύθ.: " + customer.get("address"), format);
                }
                if (customer.get("notes") != null) {
                    mPrinter.setPrintAppendString("Σημ.: " + customer.get("notes"), format);
                }
            } else {
                mPrinter.setPrintAppendString("Δεν υπάρχουν στοιχεία", format);
            }

            format.setAli(Layout.Alignment.ALIGN_CENTER);
            mPrinter.setPrintAppendString("----------------", format);
            mPrinter.setPrintAppendString("\n\n\n", format);

            int ret = mPrinter.setPrintStart();
            if (ret != SdkResult.SDK_OK) {
                runOnUiThread(() -> Toast.makeText(DeliveryOrdersActivity.this, "Σφάλμα εκτύπωσης πελάτη: " + ret, Toast.LENGTH_LONG).show());
            } else if (mPrinter.isSuppoerCutter()) {
                mPrinter.openPrnCutter((byte) 1);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001) {
            paymentManager.handlePosResult(resultCode, data);
        }
    }

    // ---------- Εκτύπωση σε δύο χαρτιά ----------
    private void printDeliveryOrder(String orderNumber, List<Map<String, Object>> items,
                                    Map<String, Object> customer,
                                    String mark, String uid, String authCode, String qrUrl) {
        runOnUiThread(() -> Toast.makeText(DeliveryOrdersActivity.this, "Εκτύπωση...", Toast.LENGTH_SHORT).show());

        printExecutor.execute(() -> {
            int printStatus = mPrinter.getPrinterStatus();
            if (printStatus == SdkResult.SDK_PRN_STATUS_PAPEROUT) {
                runOnUiThread(() -> Toast.makeText(DeliveryOrdersActivity.this, "Δεν υπάρχει χαρτί", Toast.LENGTH_LONG).show());
                return;
            }

            // ---- Πρώτο χαρτί: είδη και στοιχεία παρόχου ----
            PrnStrFormat format = new PrnStrFormat();
            format.setTextSize(25);
            format.setStyle(PrnTextStyle.NORMAL);
            format.setFont(PrnTextFont.MONOSPACE);

            format.setAli(Layout.Alignment.ALIGN_CENTER);
            mPrinter.setPrintAppendString("DELIVERY", format);
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

            // Στοιχεία παρόχου
            if (mark != null && !mark.equals("0")) {
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
            }

            format.setAli(Layout.Alignment.ALIGN_CENTER);
            mPrinter.setPrintAppendString("----------------", format);
            mPrinter.setPrintAppendString("\n\n\n", format);

            int ret1 = mPrinter.setPrintStart();
            if (ret1 != SdkResult.SDK_OK) {
                runOnUiThread(() -> Toast.makeText(DeliveryOrdersActivity.this, "Σφάλμα εκτύπωσης (είδη): " + ret1, Toast.LENGTH_LONG).show());
                return;
            }

            // Κόψιμο μεταξύ των χαρτιών
            if (mPrinter.isSuppoerCutter()) {
                mPrinter.openPrnCutter((byte) 1);
            }
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            // ---- Δεύτερο χαρτί: στοιχεία πελάτη ----
            format.setTextSize(20);
            format.setAli(Layout.Alignment.ALIGN_CENTER);
            mPrinter.setPrintAppendString("ΣΤΟΙΧΕΙΑ ΠΕΛΑΤΗ", format);
            mPrinter.setPrintAppendString("----------------", format);

            format.setAli(Layout.Alignment.ALIGN_NORMAL);
            if (customer != null) {
                mPrinter.setPrintAppendString("Όνομα: " + customer.get("name"), format);
                mPrinter.setPrintAppendString("Τηλ.: " + customer.get("phone"), format);
                if (customer.get("address") != null) {
                    mPrinter.setPrintAppendString("Διεύθ.: " + customer.get("address"), format);
                }
                if (customer.get("notes") != null) {
                    mPrinter.setPrintAppendString("Σημ.: " + customer.get("notes"), format);
                }
            } else {
                mPrinter.setPrintAppendString("Δεν υπάρχουν στοιχεία", format);
            }

            format.setAli(Layout.Alignment.ALIGN_CENTER);
            mPrinter.setPrintAppendString("----------------", format);
            mPrinter.setPrintAppendString("\n\n\n", format);

            int ret2 = mPrinter.setPrintStart();
            if (ret2 == SdkResult.SDK_OK) {
                runOnUiThread(() -> Toast.makeText(DeliveryOrdersActivity.this, "Εκτύπωση ολοκληρώθηκε", Toast.LENGTH_SHORT).show());
            } else {
                runOnUiThread(() -> Toast.makeText(DeliveryOrdersActivity.this, "Σφάλμα εκτύπωσης (πελάτης): " + ret2, Toast.LENGTH_LONG).show());
            }

            if (mPrinter.isSuppoerCutter()) {
                mPrinter.openPrnCutter((byte) 1);
            }
        });
    }

    private void cancelOrder(DeliveryOrder order) {
        new AlertDialog.Builder(this)
                .setTitle("Ακύρωση παραγγελίας")
                .setMessage("Θέλετε να ακυρώσετε την παραγγελία " + order.orderNumber + ";")
                .setPositiveButton("Ναι", (dialog, which) ->
                        deliveryRef.child(order.id).removeValue()
                                .addOnSuccessListener(aVoid -> Toast.makeText(this, "Η παραγγελία ακυρώθηκε", Toast.LENGTH_SHORT).show())
                                .addOnFailureListener(e -> Toast.makeText(this, "Σφάλμα", Toast.LENGTH_SHORT).show()))
                .setNegativeButton("Όχι", null)
                .show();
    }

    // ---------- Adapter ----------
    private class DeliveryAdapter extends RecyclerView.Adapter<DeliveryAdapter.ViewHolder> {
        private final List<DeliveryOrder> list;

        DeliveryAdapter(List<DeliveryOrder> list) { this.list = list; }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_delivery_order, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            DeliveryOrder order = list.get(position);
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

            if (order.customer != null) {
                String info = "👤 " + order.customer.get("name") + "\n📞 " + order.customer.get("phone");
                if (order.customer.get("address") != null) info += "\n📍 " + order.customer.get("address");
                if (order.customer.get("notes") != null) info += "\n📝 " + order.customer.get("notes");
                holder.tvCustomerInfo.setText(info);
            } else {
                holder.tvCustomerInfo.setText("Χωρίς στοιχεία πελάτη");
            }

            holder.btnPrint.setOnClickListener(v -> showPaymentMethodDialog(order));
            holder.btnCancel.setOnClickListener(v -> cancelOrder(order));
        }

        @Override
        public int getItemCount() { return list.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvOrderNumber, tvOrderTime, tvOrderItems, tvCustomerInfo;
            Button btnPrint, btnCancel;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvOrderNumber = itemView.findViewById(R.id.tvOrderNumber);
                tvOrderTime = itemView.findViewById(R.id.tvOrderTime);
                tvOrderItems = itemView.findViewById(R.id.tvOrderItems);
                tvCustomerInfo = itemView.findViewById(R.id.tvCustomerInfo);
                btnPrint = itemView.findViewById(R.id.btnPrint);
                btnCancel = itemView.findViewById(R.id.btnCancel);
            }
        }
    }

    static class DeliveryOrder {
        String id;
        String orderNumber;
        long timestamp;
        List<Map<String, Object>> items;
        Map<String, Object> customer;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (printExecutor != null) printExecutor.shutdownNow();
    }
}