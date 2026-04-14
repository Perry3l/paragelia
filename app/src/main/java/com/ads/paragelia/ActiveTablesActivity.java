package com.ads.paragelia;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.List;
import java.util.Map;

public class ActiveTablesActivity extends AppCompatActivity {
    private static final String TAG = "ActiveTables";
    private LinearLayout tablesContainer;
    private DatabaseReference billsRef;
    private static final int SMS_PERMISSION_CODE = 101;

    private String pendingPhone = "";
    private String pendingReceiptText = "";

    // --- ΜΕΤΑΒΛΗΤΕΣ ΓΙΑ ΤΗΝ ΠΛΗΡΩΜΗ & ΤΟ Epsilon Digital ---
    private double pendingAmount = 0.0;
    private String pendingOrderDetails = "";
    private String pendingTableNumber = "";
    private Map<String, Object> pendingTableData; // Κρατάμε τα δεδομένα του τραπεζιού
    private String pendingPosUtid = "";
    private String pendingEpsilonSignature = "";
    private int pendingPaymentType = 7;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_active_tables);

        tablesContainer = findViewById(R.id.tablesContainer);
        billsRef = FirebaseDatabase.getInstance().getReference("active_bills");

        loadActiveTables();
    }

    private void loadActiveTables() {
        billsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                tablesContainer.removeAllViews();

                if (!snapshot.exists()) {
                    TextView emptyView = new TextView(ActiveTablesActivity.this);
                    emptyView.setText("Δεν υπάρχουν ανοιχτά τραπέζια.");
                    tablesContainer.addView(emptyView);
                    return;
                }

                for (DataSnapshot tableSnapshot : snapshot.getChildren()) {
                    String tableNumber = tableSnapshot.getKey();
                    StringBuilder tableDetails = new StringBuilder();
                    for (DataSnapshot orderSnapshot : tableSnapshot.getChildren()) {
                        Map<String, Object> order = (Map<String, Object>) orderSnapshot.getValue();
                        if (order != null && order.get("items") instanceof List) {
                            List<Map<String, Object>> items = (List<Map<String, Object>>) order.get("items");

                            for (Map<String, Object> item : items) {
                                String name = (String) item.get("name");
                                Object qtyObj = item.get("quantity");
                                int quantity = (qtyObj instanceof Long) ? ((Long) qtyObj).intValue() : (int) qtyObj;

                                tableDetails.append("- ").append(name).append(" x").append(quantity).append("\n");
                            }
                        }
                    }

                    Map<String, Object> fullTableData = (Map<String, Object>) tableSnapshot.getValue();
                    createTableCard(tableNumber, tableDetails.toString(), fullTableData);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Σφάλμα ανάγνωσης λογαριασμών: " + error.getMessage());
            }
        });
    }

    private void createTableCard(String tableNumber, String details, Map<String, Object> tableData) {
        CardView card = new CardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, 24);
        card.setLayoutParams(params);
        card.setCardElevation(8f);
        card.setRadius(12f);
        card.setContentPadding(32, 32, 32, 32);
        card.setCardBackgroundColor(Color.WHITE);

        LinearLayout cardContent = new LinearLayout(this);
        cardContent.setOrientation(LinearLayout.VERTICAL);

        TextView tv = new TextView(this);
        tv.setText("Τραπέζι " + tableNumber + "\n\n" + details);
        tv.setTextSize(18f);
        tv.setTextColor(Color.BLACK);
        cardContent.addView(tv);

        LinearLayout buttonsLayout = new LinearLayout(this);
        buttonsLayout.setOrientation(LinearLayout.HORIZONTAL);
        buttonsLayout.setPadding(0, 24, 0, 0);

        android.widget.Button btnCancel = new android.widget.Button(this);
        btnCancel.setText("ΑΚΥΡΩΣΗ");
        btnCancel.setBackgroundColor(Color.parseColor("#F44336"));
        btnCancel.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams btnCancelParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnCancelParams.setMargins(0, 0, 8, 0);
        btnCancel.setLayoutParams(btnCancelParams);

        android.widget.Button btnPay = new android.widget.Button(this);
        btnPay.setText("ΕΚΔΟΣΗ ΑΠΟΔΕΙΞΗΣ");
        btnPay.setBackgroundColor(Color.parseColor("#4CAF50"));
        btnPay.setTextColor(Color.WHITE);
        btnPay.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        buttonsLayout.addView(btnCancel);
        buttonsLayout.addView(btnPay);
        cardContent.addView(buttonsLayout);
        card.addView(cardContent);
        tablesContainer.addView(card);

        card.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(ActiveTablesActivity.this, NewOrderActivity.class);
            intent.putExtra("EXTRA_TABLE_NUMBER", tableNumber);
            startActivity(intent);
        });

        btnCancel.setOnClickListener(v -> {
            billsRef.child(tableNumber).removeValue()
                    .addOnSuccessListener(aVoid -> android.widget.Toast.makeText(this, "Το τραπέζι " + tableNumber + " ακυρώθηκε", android.widget.Toast.LENGTH_SHORT).show());
        });

        // 3. Έναρξη Διαδικασίας
        btnPay.setOnClickListener(v -> {
            String[] options = {"💳 Πληρωμή με Κάρτα", "💵 Πληρωμή με Μετρητά"};
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Επιλογή Τρόπου Πληρωμής")
                    .setItems(options, (dialog, which) -> {
                        pendingTableData = tableData; // Αποθηκεύουμε τα δεδομένα του τραπεζιού
                        if (which == 0) {
                            showAmountDialog("Πληρωμή με Κάρτα", amount -> startPosPayment(amount, tableNumber, details));
                        } else if (which == 1) {
                            showAmountDialog("Πληρωμή με Μετρητά", amount -> startCashPayment(amount, tableNumber, details));
                        }
                    })
                    .setNegativeButton("Ακύρωση", null)
                    .show();
        });
    }

    private interface AmountCallback { void onAmountEntered(double amount); }
    private void showAmountDialog(String title, AmountCallback callback) {
        final android.widget.EditText inputAmount = new android.widget.EditText(this);
        inputAmount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        inputAmount.setHint("π.χ. 15.50");

        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("Πληκτρολογήστε το ποσό χρέωσης (€):")
                .setView(inputAmount)
                .setPositiveButton("ΟΚ", (dialogPos, whichPos) -> {
                    String amountStr = inputAmount.getText().toString().trim();
                    if (!amountStr.isEmpty()) {
                        try {
                            double amount = Double.parseDouble(amountStr);
                            callback.onAmountEntered(amount);
                        } catch (NumberFormatException e) {
                            android.widget.Toast.makeText(this, "Μη έγκυρο ποσό", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Ακύρωση", null)
                .show();
    }

    private void startCashPayment(double amount, String tableNumber, String orderDetails) {
        pendingTableNumber = tableNumber;
        pendingAmount = amount;
        pendingOrderDetails = orderDetails;
        pendingPaymentType = 3;
        android.widget.Toast.makeText(this, "Αποστολή στο myDATA...", android.widget.Toast.LENGTH_SHORT).show();
        sendToEpsilonProvider();
    }

    private void startPosPayment(double amount, String tableNumber, String orderDetails) {
        pendingTableNumber = tableNumber;
        pendingAmount = amount;
        pendingOrderDetails = orderDetails;
        pendingPaymentType = 7;
        android.widget.Toast.makeText(this, "Αναμονή έγκρισης από Epsilon...", android.widget.Toast.LENGTH_SHORT).show();

        double netAmount = Math.round((amount / 1.13) * 100.0) / 100.0;
        double vatAmount = Math.round((amount - netAmount) * 100.0) / 100.0;

        String orderRef = "TBL" + tableNumber + "_" + System.currentTimeMillis();
        String todayDate = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(new java.util.Date());

        com.google.gson.JsonObject requestPaymentObj = new com.google.gson.JsonObject();
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

        android.content.SharedPreferences prefs = getSharedPreferences("my_prefs", MODE_PRIVATE);
        String jwtToken = prefs.getString("jwt", null);
        String dynamicBaseUrl = prefs.getString("baseUrl", null);

        if (jwtToken == null || dynamicBaseUrl == null) return;
        String fullUrl = dynamicBaseUrl + (dynamicBaseUrl.endsWith("/") ? "" : "/") + "api/requestPayment";

        com.ads.paragelia.paroxos.RetrofitClient.getInstance().getSendService()
                .requestPayment(fullUrl, "Bearer " + jwtToken, "3.0", requestPaymentObj)
                .enqueue(new retrofit2.Callback<com.google.gson.JsonObject>() {
                    @Override
                    public void onResponse(retrofit2.Call<com.google.gson.JsonObject> call, retrofit2.Response<com.google.gson.JsonObject> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            try {
                                com.google.gson.JsonObject res = response.body();
                                String uid = res.has("uid") ? res.get("uid").getAsString() : "";
                                com.google.gson.JsonObject paymentToken = res.getAsJsonObject("paymentToken");
                                String signature = paymentToken.get("signature").getAsString();
                                String timestamp = paymentToken.get("timestamp").getAsString();

                                android.content.Intent posIntent = com.ads.paragelia.paroxos.EpayHelper.createSaleIntent(
                                        getPackageName(), amount, netAmount, vatAmount, orderRef, uid, signature, "004", timestamp, "CONTACTLESS", "22223729"
                                );
                                if (posIntent != null) startActivityForResult(posIntent, 1001);
                            } catch (Exception e) {
                                android.widget.Toast.makeText(ActiveTablesActivity.this, "Σφάλμα ανάγνωσης PaymentToken", android.widget.Toast.LENGTH_LONG).show();
                            }
                        }
                    }
                    @Override public void onFailure(retrofit2.Call<com.google.gson.JsonObject> call, Throwable t) {}
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @androidx.annotation.Nullable android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001) {
            if (data != null && resultCode == RESULT_OK) {
                try {
                    String resultJsonStr = data.getStringExtra("gr.epayworldwide.softpos.RESULT");
                    org.json.JSONObject outerJson = new org.json.JSONObject(resultJsonStr);
                    org.json.JSONObject innerJson = new org.json.JSONObject(outerJson.getString("payload"));

                    if (innerJson.getInt("resultCode") == 0) {
                        org.json.JSONObject extras = innerJson.getJSONObject("responseBody").getJSONObject("extras");
                        pendingPosUtid = extras.getString("utid");
                        pendingEpsilonSignature = extras.getString("signature");
                        android.widget.Toast.makeText(this, "Επιτυχής χρέωση! Αποστολή στο myDATA...", android.widget.Toast.LENGTH_SHORT).show();
                        sendToEpsilonProvider();
                    }
                } catch (Exception e) {}
            }
        }
    }

    private void sendToEpsilonProvider() {
        android.content.SharedPreferences prefs = getSharedPreferences("my_prefs", MODE_PRIVATE);
        String jwt = prefs.getString("jwt", null);
        String baseUrl = prefs.getString("baseUrl", null);

        if (jwt == null || baseUrl == null) return;

        double netValue = Math.round((pendingAmount / 1.13) * 100.0) / 100.0;
        double vatAmount = Math.round((pendingAmount - netValue) * 100.0) / 100.0;

        com.google.gson.JsonObject sourceObj = new com.google.gson.JsonObject();
        com.google.gson.JsonObject invoiceObj = new com.google.gson.JsonObject();

        com.google.gson.JsonObject issuerObj = new com.google.gson.JsonObject();
        issuerObj.addProperty("vatNumber", "000000000");
        issuerObj.addProperty("branch", 0);
        issuerObj.addProperty("city", "ΠΟΛΗ");
        issuerObj.addProperty("country", "GR");
        invoiceObj.add("issuer", issuerObj);

        com.google.gson.JsonObject counterpartObj = new com.google.gson.JsonObject();
        counterpartObj.add("vatNumber", null);
        counterpartObj.addProperty("name", "ΠΕΛΑΤΗΣ ΛΙΑΝΙΚΗΣ");
        counterpartObj.addProperty("country", "GR");
        counterpartObj.addProperty("branch", 0);
        invoiceObj.add("counterpart", counterpartObj);

        com.google.gson.JsonObject headerObj = new com.google.gson.JsonObject();
        headerObj.addProperty("series", "A");
        headerObj.addProperty("aa", String.valueOf(System.currentTimeMillis() % 100000));
        String todayDate = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(new java.util.Date());
        headerObj.addProperty("issueDate", todayDate);
        headerObj.addProperty("invoiceType", "11.2");
        headerObj.addProperty("currency", "EUR");
        invoiceObj.add("invoiceHeader", headerObj);

        com.google.gson.JsonArray detailsArray = new com.google.gson.JsonArray();
        com.google.gson.JsonObject line1 = new com.google.gson.JsonObject();
        line1.addProperty("lineNumber", 1);
        line1.addProperty("quantity", 1);
        line1.addProperty("entityName", "Παραγγελία Τραπεζιού " + pendingTableNumber);
        line1.addProperty("netValue", netValue);
        line1.addProperty("vatCategory", 2);
        line1.addProperty("vatAmount", vatAmount);
        line1.addProperty("vatPercent", 13);
        line1.addProperty("totalValue", pendingAmount);
        line1.addProperty("measurementUnit", 1);
        line1.addProperty("classificationCategory", "category1_3");
        line1.addProperty("classificationType", "E3_561_003");
        detailsArray.add(line1);
        invoiceObj.add("invoiceDetails", detailsArray);

        com.google.gson.JsonArray paymentsArray = new com.google.gson.JsonArray();
        com.google.gson.JsonObject payment1 = new com.google.gson.JsonObject();
        payment1.addProperty("type", pendingPaymentType);
        payment1.addProperty("amount", pendingAmount);
        if (pendingPaymentType == 7) {
            payment1.addProperty("transactionId", pendingPosUtid);
            payment1.addProperty("signature", pendingEpsilonSignature);
            payment1.addProperty("tipAmount", 0.0);
        }
        paymentsArray.add(payment1);
        invoiceObj.add("paymentMethods", paymentsArray);

        com.google.gson.JsonObject summaryObj = new com.google.gson.JsonObject();
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
                .enqueue(new retrofit2.Callback<com.ads.paragelia.paroxos.SendResponse>() {
                    @Override
                    public void onResponse(retrofit2.Call<com.ads.paragelia.paroxos.SendResponse> call, retrofit2.Response<com.ads.paragelia.paroxos.SendResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            com.ads.paragelia.paroxos.SendResponse res = response.body();
                            if (res.getStatus() == 1 || res.getMark() > 0) {
                                proceedToDelivery(res);
                            } else if (res.getStatus() == 0) {
                                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                    checkInvoiceStatus(res.getProcessId(), res.getExternalSystemId(), pendingTableNumber);
                                }, 1000);
                            }
                        }
                    }
                    @Override public void onFailure(retrofit2.Call<com.ads.paragelia.paroxos.SendResponse> call, Throwable t) {}
                });
    }

    private void checkInvoiceStatus(String processId, String externalSystemId, String tableNumber) {
        android.content.SharedPreferences prefs = getSharedPreferences("my_prefs", MODE_PRIVATE);
        String jwtToken = prefs.getString("jwt", null);
        String baseUrl = prefs.getString("baseUrl", null);
        if (jwtToken == null || baseUrl == null) return;

        String fullUrl = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "api/get";
        com.ads.paragelia.paroxos.GetStatusRequest statusReq = new com.ads.paragelia.paroxos.GetStatusRequest(processId, externalSystemId);

        com.ads.paragelia.paroxos.RetrofitClient.getInstance().getSendService()
                .getInvoiceStatus(fullUrl, "Bearer " + jwtToken, "3.0", statusReq)
                .enqueue(new retrofit2.Callback<com.ads.paragelia.paroxos.SendResponse>() {
                    @Override
                    public void onResponse(retrofit2.Call<com.ads.paragelia.paroxos.SendResponse> call, retrofit2.Response<com.ads.paragelia.paroxos.SendResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            com.ads.paragelia.paroxos.SendResponse res = response.body();
                            if (res.getStatus() == 1) {
                                proceedToDelivery(res);
                            } else if (res.getStatus() == 0) {
                                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                    checkInvoiceStatus(processId, externalSystemId, tableNumber);
                                }, 1000);
                            }
                        }
                    }
                    @Override public void onFailure(retrofit2.Call<com.ads.paragelia.paroxos.SendResponse> call, Throwable t) {}
                });
    }

    // Το τελικό βήμα: Έχουμε το MARK και ρωτάμε "Εκτύπωση ή SMS;"
    private void proceedToDelivery(com.ads.paragelia.paroxos.SendResponse res) {
        String markStr = String.valueOf(res.getMark());
        String uid = res.getUid() != null ? res.getUid() : "-";
        String authCode = res.getAuthenticationCode() != null ? res.getAuthenticationCode() : "-";
        String qrUrl = res.getQrCode() != null ? res.getQrCode() : "";

        String[] options = {"🖨️ Εκτύπωση στο Ταμείο", "📱 Αποστολή με SMS", "❌ Καμία ενέργεια"};

        new android.app.AlertDialog.Builder(this)
                .setTitle("Επιτυχία! MARK: " + markStr + "\n\nΠώς θέλετε να παραδοθεί;")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        // Εκτύπωση
                        pendingTableData.put("epsilon_mark", markStr);
                        pendingTableData.put("epsilon_uid", uid);
                        pendingTableData.put("epsilon_auth", authCode);
                        pendingTableData.put("epsilon_qr", qrUrl);

                        DatabaseReference receiptsRef = FirebaseDatabase.getInstance().getReference("receipts").child(pendingTableNumber);
                        receiptsRef.setValue(pendingTableData).addOnSuccessListener(aVoid -> {
                            billsRef.child(pendingTableNumber).removeValue();
                            android.widget.Toast.makeText(this, "Εστάλη στον εκτυπωτή!", android.widget.Toast.LENGTH_SHORT).show();
                        });
                    } else if (which == 1) {
                        // SMS
                        showSmsDialog(pendingTableNumber, pendingOrderDetails, markStr, qrUrl);
                    } else if (which == 2) {
                        // Καμία ενέργεια – απλά διαγράφουμε το τραπέζι
                        billsRef.child(pendingTableNumber).removeValue()
                                .addOnSuccessListener(aVoid -> android.widget.Toast.makeText(this, "Το τραπέζι " + pendingTableNumber + " έκλεισε χωρίς άλλη ενέργεια", android.widget.Toast.LENGTH_SHORT).show());
                    }
                })
                .setCancelable(false)
                .show();
    }

    // --- SMS METHODS ---
    private void showSmsDialog(String tableNumber, String details, String mark, String qrUrl) {
        final android.widget.EditText inputPhone = new android.widget.EditText(this);
        inputPhone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        inputPhone.setHint("π.χ. 69........");

        new android.app.AlertDialog.Builder(this)
                .setTitle("Αποστολή Απόδειξης")
                .setMessage("Πληκτρολογήστε το κινητό του πελάτη:")
                .setView(inputPhone)
                .setPositiveButton("Αποστολή", (dialogSms, whichSms) -> {
                    String phone = inputPhone.getText().toString().trim();
                    if (!phone.isEmpty()) {
                        String receiptText = "ΑΠΟΔΕΙΞΗ ΠΑΡΑΓΓΕΛΙΑΣ\nΤραπέζι: " + tableNumber + "\n------------------\n"
                                + details + "------------------\nMARK: " + mark + "\nΔείτε την απόδειξη εδώ: " + qrUrl + "\nΕυχαριστούμε!";

                        if (androidx.core.content.ContextCompat.checkSelfPermission(ActiveTablesActivity.this, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            pendingPhone = phone;
                            pendingReceiptText = receiptText;
                            pendingTableNumber = tableNumber;
                            androidx.core.app.ActivityCompat.requestPermissions(ActiveTablesActivity.this, new String[]{android.Manifest.permission.SEND_SMS}, SMS_PERMISSION_CODE);
                        } else {
                            sendSmsDirectly(phone, receiptText, tableNumber);
                        }
                    }
                })
                .setNegativeButton("Ακύρωση", null)
                .show();
    }

    private void sendSmsDirectly(String phoneNumber, String receiptText, String tableNumber) {
        try {
            android.telephony.SmsManager smsManager = android.telephony.SmsManager.getDefault();
            java.util.ArrayList<String> parts = smsManager.divideMessage(receiptText);
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null);
            android.widget.Toast.makeText(this, "Το SMS εστάλη!", android.widget.Toast.LENGTH_SHORT).show();
            billsRef.child(tableNumber).removeValue(); // Διαγραφή τραπεζιού μετά το SMS
        } catch (Exception e) {}
    }
}