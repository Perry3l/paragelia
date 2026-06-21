package com.ads.paragelia;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.ads.paragelia.paroxos.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class PaymentManager {
    public interface PaymentCallback {
        void onSuccess(SendResponse response);
        void onError(String message);
    }

    private final Activity activity;
    private final SharedPreferences prefs;
    private final ExecutorService paymentExecutor = Executors.newSingleThreadExecutor();

    private double pendingAmount;
    private String pendingOrderDetails;
    private String pendingTableNumber;
    private List<Map<String, Object>> pendingPaymentItems;
    private int pendingPaymentType;
    private PaymentCallback pendingCallback;
    private String pendingPosUtid = "";
    private String pendingEpsilonSignature = "";

    public PaymentManager(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences("my_prefs", Activity.MODE_PRIVATE);
    }

    public void startCashPayment(double amount, String tableNumber, String orderDetails,
                                 List<Map<String, Object>> items, PaymentCallback callback) {
        this.pendingAmount = amount;
        this.pendingTableNumber = tableNumber;
        this.pendingOrderDetails = orderDetails;
        this.pendingPaymentItems = items;
        this.pendingPaymentType = 3;
        this.pendingCallback = callback;
        this.pendingPosUtid = "";
        this.pendingEpsilonSignature = "";

        activity.runOnUiThread(() ->
                Toast.makeText(activity, "Αποστολή στο myDATA...", Toast.LENGTH_SHORT).show()
        );
        sendToEpsilonProvider();
    }


    public void startPosPayment(double amount, String tableNumber, String details,
                                List<Map<String, Object>> items, PaymentCallback callback) {
        this.pendingAmount = amount;
        this.pendingTableNumber = tableNumber;
        this.pendingOrderDetails = details;
        this.pendingPaymentItems = items;
        this.pendingPaymentType = 7;
        this.pendingCallback = callback;
        this.pendingPosUtid = "";
        this.pendingEpsilonSignature = "";

        long originalMark = 0;

        activity.runOnUiThread(() ->
                Toast.makeText(activity, "Αναμονή έγκρισης POS από Epsilon Digital...", Toast.LENGTH_SHORT).show()
        );

        attemptRequestPayment(originalMark, amount, tableNumber);
    }


    private void attemptRequestPayment(long originalMark, double amount, String tableNumber) {
        EpsilonIntegrationHelper.requestPayment(activity, originalMark, amount, "order_" + tableNumber + "_" + System.currentTimeMillis(),
                new EpsilonIntegrationHelper.CallbackWithResult<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject response) {
                        if (response.has("paymentToken") && response.get("paymentToken").isJsonObject()) {
                            JsonObject tokenObj = response.getAsJsonObject("paymentToken");

                            String uid = response.has("uid") && !response.get("uid").isJsonNull() ?
                                    response.get("uid").getAsString() : "";

                            String signature = tokenObj.has("signature") && !tokenObj.get("signature").isJsonNull() ?
                                    tokenObj.get("signature").getAsString() : "";

                            String timestamp = tokenObj.has("timestamp") && !tokenObj.get("timestamp").isJsonNull() ?
                                    tokenObj.get("timestamp").getAsString() : "";

                            double netAmount = amount / 1.13;
                            double vatAmount = amount - netAmount;

                            Intent posIntent = EpayHelper.createSaleIntent(
                                    activity.getPackageName(),
                                    amount,
                                    netAmount,
                                    vatAmount,
                                    "order_" + tableNumber + "_" + System.currentTimeMillis(),
                                    uid,
                                    signature,
                                    "epsilon",
                                    timestamp,
                                    "card",
                                    "1234567890"
                            );

                            if (posIntent != null) {
                                activity.startActivityForResult(posIntent, 1001);
                            } else {
                                if (pendingCallback != null) pendingCallback.onError("Αποτυχία δημιουργίας εντολής προς το SoftPOS.");
                            }
                        } else {
                            if (pendingCallback != null) pendingCallback.onError("Το Epsilon Digital δεν επέστρεψε έγκυρο αντικείμενο Payment Token.");
                        }
                    }

                    @Override
                    public void onError(String message) {
                        if (message.contains("401")) {
                            Log.w("PaymentManager", "Λήξη Token (401) στο requestPayment. Ασφαλής ανανέωση κωδικών POS...");
                            refreshTokenAndRetryPosPayment(originalMark, amount, tableNumber);
                        } else {
                            if (pendingCallback != null) pendingCallback.onError("Σφάλμα Πάροχου στο POS: " + message);
                        }
                    }
                });
    }


    private void refreshTokenAndRetryPosPayment(long originalMark, double amount, String tableNumber) {
        String refreshToken = prefs.getString("refreshToken", null);
        String oldJwt = prefs.getString("jwt", null);

        if (refreshToken == null || oldJwt == null) {
            performFullLoginForPos(originalMark, amount, tableNumber);
            return;
        }

        RefreshRequest refreshRequest = new RefreshRequest(oldJwt, refreshToken);
        RetrofitClient.getInstance().getApiService()
                .refreshToken(refreshRequest)
                .enqueue(new Callback<LoginResponse>() {
                    @Override
                    public void onResponse(Call<LoginResponse> call, Response<LoginResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            LoginResponse loginData = response.body();
                            prefs.edit()
                                    .putString("jwt", loginData.getJwt())
                                    .putString("refreshToken", loginData.getJwtRefreshToken())
                                    .apply();

                            Log.i("PaymentManager", "Επιτυχές Refresh! Επανάληψη αιτήματος POS...");
                            attemptRequestPayment(originalMark, amount, tableNumber);
                        } else {
                            performFullLoginForPos(originalMark, amount, tableNumber);
                        }
                    }

                    @Override
                    public void onFailure(Call<LoginResponse> call, Throwable t) {
                        performFullLoginForPos(originalMark, amount, tableNumber);
                    }
                });
    }


    private void performFullLoginForPos(long originalMark, double amount, String tableNumber) {
        LoginRequest request = new LoginRequest(
                "1ADB7B09478F4C58892ADDBB23E0EF65",
                "EA824C0CDE234554AFEE",
                "Vvt8UTjQO_TO/CVkAcG9"
        );
        RetrofitClient.getInstance().getApiService()
                .loginToSubscription(request)
                .enqueue(new Callback<LoginResponse>() {
                    @Override
                    public void onResponse(Call<LoginResponse> call, Response<LoginResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            LoginResponse loginData = response.body();
                            prefs.edit()
                                    .putString("jwt", loginData.getJwt())
                                    .putString("refreshToken", loginData.getJwtRefreshToken())
                                    .putString("baseUrl", loginData.getUrl1())
                                    .apply();

                            Log.i("PaymentManager", "Επιτυχές ολικό Login! Επανάληψη αιτήματος POS...");
                            attemptRequestPayment(originalMark, amount, tableNumber);
                        } else {
                            if (pendingCallback != null) pendingCallback.onError("Αποτυχία επανασύνδεσης (Login) για το POS.");
                        }
                    }

                    @Override
                    public void onFailure(Call<LoginResponse> call, Throwable t) {
                        if (pendingCallback != null) pendingCallback.onError("Σφάλμα δικτύου στο Login: " + t.getMessage());
                    }
                });
    }

    public void handlePosResult(int resultCode, Intent data) {
        if (pendingCallback == null) return;

        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            String configJson = data.getStringExtra("gr.epayworldwide.softpos.CONFIGURATION");

            try {
                JSONObject root = new JSONObject(configJson != null ? configJson : "{}");
                String signature = root.optString("signature", "");

                JSONObject payload = new JSONObject(root.optString("payload", "{}"));
                JSONObject responseData = payload.optJSONObject("data");

                String transactionId = "";
                if (responseData != null) {
                    transactionId = responseData.optString("transaction_id", responseData.optString("rrn", "pos_tx_" + System.currentTimeMillis()));
                }

                activity.runOnUiThread(() ->
                        Toast.makeText(activity, "Η κάρτα εγκρίθηκε! Οριστική έκδοση 11.2...", Toast.LENGTH_SHORT).show()
                );

                this.pendingPosUtid = transactionId;
                this.pendingEpsilonSignature = signature;

                sendToEpsilonProvider();

            } catch (Exception e) {
                pendingCallback.onError("Σφάλμα ανάγνωσης δεδομένων SoftPOS: " + e.getMessage());
            }
        } else {
            pendingCallback.onError("Η συναλλαγή με κάρτα ακυρώθηκε ή απέτυχε στο τερματικό.");
        }
    }

    private void sendToEpsilonProvider() {
        SharedPreferences prefs = activity.getSharedPreferences("my_prefs", Activity.MODE_PRIVATE);
        String jwt = prefs.getString("jwt", null);
        String baseUrl = prefs.getString("baseUrl", null);
        if (jwt == null || baseUrl == null) {
            if (pendingCallback != null) pendingCallback.onError("No auth");
            return;
        }

        double netValue, vatAmount;
        JsonArray detailsArray = new JsonArray();

        if (pendingPaymentItems != null && !pendingPaymentItems.isEmpty()) {
            double sumNet = 0, sumVat = 0;
            Map<Integer, double[]> grouped = new HashMap<>();
            for (Map<String, Object> item : pendingPaymentItems) {
                double price = ((Number) item.get("price")).doubleValue();
                int qty = ((Number) item.get("quantity")).intValue();
                double vatPct = item.containsKey("vatPercent") ? ((Number) item.get("vatPercent")).doubleValue() : 13.0;
                double totalItem = price * qty;
                double netItem = totalItem / (1 + vatPct / 100.0);
                double vatItem = totalItem - netItem;
                sumNet += netItem;
                sumVat += vatItem;

                int vatKey = (int) Math.round(vatPct);
                if (!grouped.containsKey(vatKey)) grouped.put(vatKey, new double[]{0, 0});
                double[] sums = grouped.get(vatKey);
                sums[0] += netItem;
                sums[1] += vatItem;
            }
            netValue = Math.round(sumNet * 100.0) / 100.0;
            vatAmount = Math.round(sumVat * 100.0) / 100.0;

            int lineNum = 1;
            for (Map.Entry<Integer, double[]> entry : grouped.entrySet()) {
                int vatPct = entry.getKey();
                double[] sums = entry.getValue();
                JsonObject line = new JsonObject();
                line.addProperty("lineNumber", lineNum++);
                line.addProperty("quantity", 1);
                line.addProperty("entityName", "Παραγγελία Τραπεζιού " + pendingTableNumber);
                line.addProperty("netValue", Math.round(sums[0] * 100.0) / 100.0);
                line.addProperty("vatCategory", vatPct == 0 ? 1 : 2);
                line.addProperty("vatAmount", Math.round(sums[1] * 100.0) / 100.0);
                line.addProperty("vatPercent", vatPct);
                line.addProperty("totalValue", Math.round((sums[0] + sums[1]) * 100.0) / 100.0);
                line.addProperty("measurementUnit", 1);
                line.addProperty("classificationCategory", "category1_3");
                line.addProperty("classificationType", "E3_561_003");
                detailsArray.add(line);
            }
        } else {
            netValue = Math.round((pendingAmount / 1.13) * 100.0) / 100.0;
            vatAmount = Math.round((pendingAmount - netValue) * 100.0) / 100.0;
            JsonObject line = new JsonObject();
            line.addProperty("lineNumber", 1);
            line.addProperty("quantity", 1);
            line.addProperty("entityName", "Παραγγελία Τραπεζιού " + pendingTableNumber);
            line.addProperty("netValue", netValue);
            line.addProperty("vatCategory", 2);
            line.addProperty("vatAmount", vatAmount);
            line.addProperty("vatPercent", 13);
            line.addProperty("totalValue", pendingAmount);
            line.addProperty("measurementUnit", 1);
            line.addProperty("classificationCategory", "category1_3");
            line.addProperty("classificationType", "E3_561_003");
            detailsArray.add(line);
        }

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

        invoiceObj.add("invoiceDetails", detailsArray);

        JsonArray paymentsArray = new JsonArray();
        JsonObject payment1 = new JsonObject();
        payment1.addProperty("type", pendingPaymentType);
        payment1.addProperty("amount", pendingAmount);
        if (pendingPaymentType == 7) {
            payment1.addProperty("transactionId", pendingPosUtid);
            payment1.addProperty("signature", pendingEpsilonSignature);
            payment1.addProperty("tipAmount", 0.0);
        }
        paymentsArray.add(payment1);
        invoiceObj.add("paymentMethods", paymentsArray);

        JsonObject summaryObj = new JsonObject();
        summaryObj.addProperty("totalNetValue", netValue);
        summaryObj.addProperty("totalVatAmount", vatAmount);
        summaryObj.addProperty("totalValue", pendingAmount);
        invoiceObj.add("invoiceSummary", summaryObj);

        sourceObj.add("invoice", invoiceObj);

        SendRequest sendRequest = new SendRequest(
                "SYS_" + System.currentTimeMillis(), "eInvoicing", 5, sourceObj
        );

        String fullUrl = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "api/send";
        RetrofitClient.getInstance().getSendService()
                .sendInvoice(fullUrl, "Bearer " + jwt, "3.0", sendRequest)
                .enqueue(new Callback<SendResponse>() {
                    @Override
                    public void onResponse(Call<SendResponse> call, Response<SendResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            SendResponse res = response.body();
                            if (res.getStatus() == 1 || res.getMark() > 0) {
                                if (pendingCallback != null) pendingCallback.onSuccess(res);
                            } else if (res.getStatus() == 0) {
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    checkInvoiceStatus(res.getProcessId(), res.getExternalSystemId());
                                }, 1000);
                            }
                        } else if (response.code() == 401) {
                            refreshTokenAndRetry();
                        } else {
                            if (pendingCallback != null) pendingCallback.onError("Server error: " + response.code());
                        }
                    }
                    @Override public void onFailure(Call<SendResponse> call, Throwable t) {
                        if (pendingCallback != null) pendingCallback.onError(t.getMessage());
                    }
                });
    }

    private void checkInvoiceStatus(String processId, String externalSystemId) {
        SharedPreferences prefs = activity.getSharedPreferences("my_prefs", Activity.MODE_PRIVATE);
        String jwtToken = prefs.getString("jwt", null);
        String baseUrl = prefs.getString("baseUrl", null);
        if (jwtToken == null || baseUrl == null) return;

        String fullUrl = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "api/get";
        GetStatusRequest statusReq = new GetStatusRequest(processId, externalSystemId);

        RetrofitClient.getInstance().getSendService()
                .getInvoiceStatus(fullUrl, "Bearer " + jwtToken, "3.0", statusReq)
                .enqueue(new Callback<SendResponse>() {
                    @Override
                    public void onResponse(Call<SendResponse> call, Response<SendResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            SendResponse res = response.body();
                            if (res.getStatus() == 1) {
                                if (pendingCallback != null) pendingCallback.onSuccess(res);
                            } else if (res.getStatus() == 0) {
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    checkInvoiceStatus(processId, externalSystemId);
                                }, 1000);
                            }
                        }
                    }
                    @Override public void onFailure(Call<SendResponse> call, Throwable t) {}
                });
    }

    private void refreshTokenAndRetry() {
        String refreshToken = prefs.getString("refreshToken", null);
        if (refreshToken == null) {
            if (pendingCallback != null) pendingCallback.onError("No refresh token");
            return;
        }
        String oldJwt = prefs.getString("jwt", null);
        RefreshRequest refreshRequest = new RefreshRequest(oldJwt, refreshToken);

        RetrofitClient.getInstance().getApiService()
                .refreshToken(refreshRequest)
                .enqueue(new Callback<LoginResponse>() {
                    @Override
                    public void onResponse(Call<LoginResponse> call, Response<LoginResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            LoginResponse loginData = response.body();
                            prefs.edit()
                                    .putString("jwt", loginData.getJwt())
                                    .putString("refreshToken", loginData.getJwtRefreshToken())
                                    .apply();
                            sendToEpsilonProvider();
                        } else {
                            performFullLogin();
                        }
                    }
                    @Override
                    public void onFailure(Call<LoginResponse> call, Throwable t) {
                        performFullLogin();
                    }
                });
    }

    private void performFullLogin() {
        LoginRequest request = new LoginRequest(
                "1ADB7B09478F4C58892ADDBB23E0EF65",
                "EA824C0CDE234554AFEE",
                "Vvt8UTjQO_TO/CVkAcG9"
        );
        RetrofitClient.getInstance().getApiService()
                .loginToSubscription(request)
                .enqueue(new Callback<LoginResponse>() {
                    @Override
                    public void onResponse(Call<LoginResponse> call, Response<LoginResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            LoginResponse loginData = response.body();
                            prefs.edit()
                                    .putString("jwt", loginData.getJwt())
                                    .putString("refreshToken", loginData.getJwtRefreshToken())
                                    .putString("baseUrl", loginData.getUrl1())
                                    .apply();
                            sendToEpsilonProvider();
                        } else {
                            if (pendingCallback != null) pendingCallback.onError("Login failed");
                        }
                    }
                    @Override
                    public void onFailure(Call<LoginResponse> call, Throwable t) {
                        if (pendingCallback != null) pendingCallback.onError(t.getMessage());
                    }
                });
    }
}