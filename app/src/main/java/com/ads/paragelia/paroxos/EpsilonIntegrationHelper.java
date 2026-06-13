package com.ads.paragelia.paroxos;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class EpsilonIntegrationHelper {
    private static final String TAG = "EpsilonIntegration";
    private static final String PREFS_NAME = "my_prefs";
    private static final String VERSION = "3.0"; // Υποχρεωτικό Header έκδοσης API

    public interface CallbackWithResult<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    /**
     * ΦΑΣΗ 1: Αποστολή Δελτίου Παραγγελίας (8.6) με ΕΞΥΠΝΗ ΑΥΤΟ-ΙΑΣΗ (SILENT FALLBACK)
     */
    public static void sendOrderSlip86(Context context, String tableNumber,
                                       List<Map<String, Object>> items,
                                       boolean isAlreadyOpen,
                                       CallbackWithResult<SendResponse> callback) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String jwt = prefs.getString("jwt", "");
        String baseUrl = prefs.getString("baseUrl", "https://beta-epsilondigital.epsilonnet.gr/");
        String url = baseUrl + "api/send";
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault());
        String issueDateTime = sdf.format(new Date());

        JsonObject source = buildOrderSlipSource(tableNumber, items, isAlreadyOpen);

        String uniqueExternalId = "order_" + tableNumber + "_" + UUID.randomUUID().toString().substring(0, 12);

        SendRequest request = new SendRequest(
                uniqueExternalId,
                "eInvoicing",
                0,
                source
        );

        Log.i(TAG, "Αποστολή 8.6 (Τραπέζι " + tableNumber + ") | openPointOfService: " + (isAlreadyOpen ? "0" : "1") + " | extId: " + uniqueExternalId);

        sendWithPossibleRefresh(context, url, jwt, request, tableNumber, items, isAlreadyOpen, new CallbackWithResult<SendResponse>() {
            @Override
            public void onSuccess(SendResponse result) {
                Log.i(TAG, "Το αρχικό αίτημα έγινε δεκτό. Ξεκινάει έλεγχος Polling...");
                pollForStatusWithFallback(context, null, uniqueExternalId, tableNumber, items, isAlreadyOpen, callback);
            }

            @Override
            public void onError(String message) {
                if (message != null && message.contains("είναι ήδη ανοιχτό") && !isAlreadyOpen) {
                    Log.w(TAG, "Silent Fallback (Send): Το Epsilon θυμάται το τραπέζι ανοιχτό. Αστραπιαία επανάληψη με openPointOfService = 0...");
                    sendOrderSlip86(context, tableNumber, items, true, callback);
                } else {
                    callback.onError(message);
                }
            }
        });
    }

    /**
     * ΑΣΦΑΛΕΣ POLLING ΜΕ ΕΝΣΩΜΑΤΩΜΕΝΟ JSON HOISTING & FALLBACK
     */
    public static void pollForStatusWithFallback(Context context, String processId, String externalId,
                                                 String tableNumber, List<Map<String, Object>> items,
                                                 boolean wasAlreadyOpen,
                                                 CallbackWithResult<SendResponse> callback) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String jwt = prefs.getString("jwt", "");
        String baseUrl = prefs.getString("baseUrl", "https://beta-epsilondigital.epsilonnet.gr/");
        String url = baseUrl + "api/get";

        new Thread(() -> {
            try {
                JSONObject bodyJson = new JSONObject();
                if (processId != null && !processId.isEmpty()) {
                    bodyJson.put("processId", processId);
                } else {
                    bodyJson.put("externalSystemId", externalId);
                }

                OkHttpClient client = new OkHttpClient();
                RequestBody body = RequestBody.create(
                        MediaType.parse("application/json; charset=utf-8"),
                        bodyJson.toString()
                );

                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + jwt)
                        .addHeader("X-Version", VERSION)
                        .post(body)
                        .build();

                okhttp3.Response response = client.newCall(request).execute();
                if (response.isSuccessful() && response.body() != null) {
                    String resStr = response.body().string();
                    Gson gson = new Gson();
                    JsonObject obj = gson.fromJson(resStr, JsonObject.class);
                    int status = obj.has("status") && !obj.get("status").isJsonNull() ? obj.get("status").getAsInt() : 2;

                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (status == 1) {
                            Log.i(TAG, "Επιτυχία! Το παραστατικό πήρε οριστικό ΜΑΡΚ.");

                            // --- JSON HOISTING ---
                            if (obj.has("signing") && obj.get("signing").isJsonObject()) {
                                JsonObject signingObj = obj.getAsJsonObject("signing");
                                if (signingObj.has("mark")) obj.add("mark", signingObj.get("mark"));
                                if (signingObj.has("uid")) obj.add("uid", signingObj.get("uid"));
                                if (signingObj.has("qrCode")) obj.add("qrCode", signingObj.get("qrCode"));
                            }

                            SendResponse finalRes = gson.fromJson(obj, SendResponse.class);

                            final long safeMark = obj.has("mark") && !obj.get("mark").isJsonNull() ? obj.get("mark").getAsLong() : 0;
                            final String safeUid = obj.has("uid") && !obj.get("uid").isJsonNull() ? obj.get("uid").getAsString() : "";
                            final String safeQr = obj.has("qrCode") && !obj.get("qrCode").isJsonNull() ? obj.get("qrCode").getAsString() : "";

                            if (finalRes == null || (safeMark > 0 && finalRes.getMark() == 0)) {
                                SendResponse overrideRes = new SendResponse() {
                                    @Override public long getMark() { return safeMark; }
                                    @Override public String getUid() { return safeUid; }
                                    @Override public String getQrCode() { return safeQr; }
                                    @Override public String getExternalSystemId() { return externalId; }
                                };
                                callback.onSuccess(overrideRes);
                            } else {
                                callback.onSuccess(finalRes);
                            }
                        } else if (status == 0) {
                            new Handler().postDelayed(() ->
                                    pollForStatusWithFallback(context, processId, externalId, tableNumber, items, wasAlreadyOpen, callback), 1500);
                        } else {
                            String errMsg = obj.has("errorMessage") && !obj.get("errorMessage").isJsonNull() ?
                                    obj.get("errorMessage").getAsString() : "Αποτυχία σήμανσης";

                            if (errMsg.contains("είναι ήδη ανοιχτό") && !wasAlreadyOpen) {
                                Log.w(TAG, "Silent Fallback (Polling): Το Epsilon απέρριψε ασύγχρονα το άνοιγμα. Επανάληψη με openPointOfService = 0...");
                                sendOrderSlip86(context, tableNumber, items, true, callback);
                            } else {
                                Log.e(TAG, "Σφάλμα Polling: " + errMsg);
                                callback.onError(errMsg);
                            }
                        }
                    });
                } else {
                    int code = response.code();
                    new Handler(Looper.getMainLooper()).post(() -> callback.onError("Σφάλμα HTTP " + code));
                }
            } catch (Exception e) {
                String exMsg = e.getMessage();
                new Handler(Looper.getMainLooper()).post(() -> callback.onError(exMsg));
            }
        }).start();
    }

    public static void pollForStatus(Context context, String processId, String externalId, CallbackWithResult<SendResponse> callback) {
        pollForStatusWithFallback(context, processId, externalId, "", null, true, callback);
    }

    /**
     * ΦΑΣΗ 3: Αποστολή Τελικής Απόδειξης (11.2)
     */
    public static void sendFinalReceipt112(Context context, String tableNumber,
                                           List<Map<String, Object>> items,
                                           long originalMark, String originalExtId,
                                           String posTransactionId, String posSignature,
                                           double posAmount,
                                           CallbackWithResult<SendResponse> callback) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String jwt = prefs.getString("jwt", "");
        String baseUrl = prefs.getString("baseUrl", "https://beta-epsilondigital.epsilonnet.gr/");
        String url = baseUrl + "api/send";

        JsonObject source = buildFinalReceiptSource(tableNumber, items, originalMark, originalExtId, posTransactionId, posSignature, posAmount);
        String uniqueExternalId = "final_" + tableNumber + "_" + UUID.randomUUID().toString();

        SendRequest request = new SendRequest(
                uniqueExternalId,
                "eInvoicing",
                0,
                source
        );

        Log.i(TAG, "Αποστολή τελικής απόδειξης 11.2 για Τραπέζι " + tableNumber + " συσχετισμένη με ΜΑΡΚ: " + originalMark);

        sendWithPossibleRefresh(context, url, jwt, request, tableNumber, items, false, new CallbackWithResult<SendResponse>() {
            @Override
            public void onSuccess(SendResponse result) {
                Log.i(TAG, "Αποδοχή 11.2. Εκκίνηση polling για το τελικό ΜΑΡΚ εξόφλησης...");
                pollForStatus(context, null, uniqueExternalId, callback);
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "Σφάλμα αποστολής 11.2: " + message);
                callback.onError(message);
            }
        });
    }

    private static void sendWithPossibleRefresh(Context context, String url, String jwt,
                                                SendRequest request,
                                                String tableNumber,
                                                List<Map<String, Object>> items,
                                                boolean isAlreadyOpen,
                                                CallbackWithResult<SendResponse> callback) {
        RetrofitClient.getInstance().getSendService()
                .sendInvoice(url, "Bearer " + jwt, VERSION, request)
                .enqueue(new retrofit2.Callback<SendResponse>() {
                    @Override
                    public void onResponse(Call<SendResponse> call, retrofit2.Response<SendResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            SendResponse res = response.body();

                            if (res.getErrorCode() == null && res.getErrorMessage() == null) {
                                callback.onSuccess(res);
                            } else {
                                String errMsg = res.getErrorMessage() != null ? res.getErrorMessage() : "";

                                if (errMsg.contains("είναι ήδη ανοιχτό") && !isAlreadyOpen) {
                                    Log.w(TAG, "Αυτόματο Fallback: Το τραπέζι βρέθηκε ανοιχτό στην Epsilon. Επαναπροώθηση με openPointOfService = 0...");
                                    JsonObject newSource = buildOrderSlipSource(tableNumber, items, true);
                                    String newUniqueId = "order_" + tableNumber + "_retry_" + UUID.randomUUID().toString().substring(0, 8);
                                    SendRequest newReq = new SendRequest(newUniqueId, "eInvoicing", 0, newSource);
                                    sendWithPossibleRefresh(context, url, jwt, newReq, tableNumber, items, true, callback);
                                } else {
                                    callback.onError(errMsg);
                                }
                            }
                        } else if (response.code() == 401) {
                            Log.w(TAG, "Λήξη Token (401). Εκκίνηση διαδικασίας ανανέωσης (Refresh)...");
                            refreshTokenAndRetry(context, tableNumber, items, isAlreadyOpen, callback);
                        } else {
                            callback.onError("HTTP " + response.code());
                        }
                    }

                    @Override
                    public void onFailure(Call<SendResponse> call, Throwable t) {
                        callback.onError(t.getMessage());
                    }
                });
    }

    private static void refreshTokenAndRetry(Context context, String tableNumber,
                                             List<Map<String, Object>> items,
                                             boolean isAlreadyOpen,
                                             CallbackWithResult<SendResponse> callback) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String refreshToken = prefs.getString("refreshToken", null);
        String oldJwt = prefs.getString("jwt", null);

        if (refreshToken == null || oldJwt == null) {
            performFullLoginAndRetry(context, tableNumber, items, isAlreadyOpen, callback);
            return;
        }

        RefreshRequest refreshRequest = new RefreshRequest(oldJwt, refreshToken);
        RetrofitClient.getInstance().getApiService()
                .refreshToken(refreshRequest)
                .enqueue(new retrofit2.Callback<LoginResponse>() {
                    @Override
                    public void onResponse(Call<LoginResponse> call, retrofit2.Response<LoginResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            LoginResponse loginData = response.body();
                            prefs.edit()
                                    .putString("jwt", loginData.getJwt())
                                    .putString("refreshToken", loginData.getJwtRefreshToken())
                                    .apply();

                            String newJwt = loginData.getJwt();
                            String baseUrl = prefs.getString("baseUrl", "https://beta-epsilondigital.epsilonnet.gr/");
                            String url = baseUrl + "api/send";
                            JsonObject source = buildOrderSlipSource(tableNumber, items, isAlreadyOpen);
                            SendRequest request = new SendRequest(
                                    "order_" + tableNumber + "_" + UUID.randomUUID().toString(),
                                    "eInvoicing",
                                    0,
                                    source
                            );
                            sendWithPossibleRefresh(context, url, newJwt, request, tableNumber, items, isAlreadyOpen, callback);
                        } else {
                            performFullLoginAndRetry(context, tableNumber, items, isAlreadyOpen, callback);
                        }
                    }

                    @Override
                    public void onFailure(Call<LoginResponse> call, Throwable t) {
                        performFullLoginAndRetry(context, tableNumber, items, isAlreadyOpen, callback);
                    }
                });
    }

    private static void performFullLoginAndRetry(Context context, String tableNumber,
                                                 List<Map<String, Object>> items,
                                                 boolean isAlreadyOpen,
                                                 CallbackWithResult<SendResponse> callback) {
        LoginRequest request = new LoginRequest(
                "1ADB7B09478F4C58892ADDBB23E0EF65",
                "EA824C0CDE234554AFEE",
                "Vvt8UTjQO_TO/CVkAcG9"
        );
        RetrofitClient.getInstance().getApiService()
                .loginToSubscription(request)
                .enqueue(new retrofit2.Callback<LoginResponse>() {
                    @Override
                    public void onResponse(Call<LoginResponse> call, retrofit2.Response<LoginResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            LoginResponse loginData = response.body();
                            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                            prefs.edit()
                                    .putString("jwt", loginData.getJwt())
                                    .putString("refreshToken", loginData.getJwtRefreshToken())
                                    .putString("baseUrl", loginData.getUrl1())
                                    .apply();

                            String newJwt = loginData.getJwt();
                            String baseUrl = loginData.getUrl1();
                            String url = baseUrl + "api/send";
                            JsonObject source = buildOrderSlipSource(tableNumber, items, isAlreadyOpen);
                            SendRequest sendReq = new SendRequest(
                                    "order_" + tableNumber + "_" + UUID.randomUUID().toString(),
                                    "eInvoicing",
                                    0,
                                    source
                            );
                            sendWithPossibleRefresh(context, url, newJwt, sendReq, tableNumber, items, isAlreadyOpen, callback);
                        } else {
                            if (callback != null) callback.onError("Login failed after 401");
                        }
                    }

                    @Override
                    public void onFailure(Call<LoginResponse> call, Throwable t) {
                        if (callback != null) callback.onError(t.getMessage());
                    }
                });
    }

    /**
     * ΦΑΣΗ 2: Αίτημα Payment Token για το POS
     */
    public static void requestPayment(Context context, long mark, double amount,
                                      String orderRef,
                                      CallbackWithResult<JsonObject> callback) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String jwt = prefs.getString("jwt", "");
        String baseUrl = prefs.getString("baseUrl", "https://beta-epsilondigital.epsilonnet.gr/");
        String url = baseUrl + "api/requestPayment";

        JsonObject paymentReq = new JsonObject();
        String uniquePayId = "pay_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);

        paymentReq.addProperty("externalSystemId", uniquePayId);
        paymentReq.addProperty("issuerVatNumber", "000000000"); // Αντικαταστήστε με το πραγματικό ΑΦΜ
        String todayIso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(new Date());
        paymentReq.addProperty("invoiceIssueDate", todayIso);

        // ΔΙΟΡΘΩΣΗ: Το API περιμένει το companyBranch αυστηρά ως String ("0") και όχι ως ακέραιο (0)
        paymentReq.addProperty("companyBranch", "0");

        paymentReq.addProperty("invoiceType", "11.2");
        paymentReq.addProperty("invoiceSeries", "A");
        paymentReq.addProperty("invoiceAA", String.valueOf(System.currentTimeMillis() % 100000));
        paymentReq.addProperty("mark", mark);

        double netVal = amount / 1.13;
        double vatVal = amount - netVal;
        paymentReq.addProperty("netValue", Math.round(netVal * 100.0) / 100.0);
        paymentReq.addProperty("vatAmount", Math.round(vatVal * 100.0) / 100.0);
        paymentReq.addProperty("totalValue", Math.round(amount * 100.0) / 100.0);
        paymentReq.addProperty("paymentAmount", Math.round(amount * 100.0) / 100.0);

        paymentReq.addProperty("terminalId", "1234567890"); // Αντικαταστήστε με το πραγματικό Terminal ID
        paymentReq.addProperty("specialInvoiceCategory", 12);
        paymentReq.addProperty("nspCode", "8");

        RetrofitClient.getInstance().getSendService()
                .requestPayment(url, "Bearer " + jwt, VERSION, paymentReq)
                .enqueue(new retrofit2.Callback<JsonObject>() {
                    @Override
                    public void onResponse(Call<JsonObject> call, retrofit2.Response<JsonObject> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            callback.onSuccess(response.body());
                        } else {
                            callback.onError("HTTP " + response.code());
                        }
                    }
                    @Override
                    public void onFailure(Call<JsonObject> call, Throwable t) {
                        callback.onError(t.getMessage());
                    }
                });
    }

    public static void submitPayment(Context context, long mark, String paymentToken,
                                     String transactionId, String signature,
                                     double amount, String orderRef,
                                     CallbackWithResult<SendResponse> callback) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String jwt = prefs.getString("jwt", "");

        JsonObject submitReq = new JsonObject();
        submitReq.addProperty("mark", mark);
        submitReq.addProperty("paymentToken", paymentToken);
        submitReq.addProperty("transactionId", transactionId);
        submitReq.addProperty("signature", signature);
        submitReq.addProperty("amount", amount);
        submitReq.addProperty("orderReference", orderRef);

        RetrofitClient.getInstance().getSendService()
                .submitPayment("Bearer " + jwt, VERSION, submitReq)
                .enqueue(new retrofit2.Callback<SendResponse>() {
                    @Override
                    public void onResponse(Call<SendResponse> call, retrofit2.Response<SendResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            SendResponse res = response.body();
                            if (res.getErrorCode() == null) {
                                callback.onSuccess(res);
                            } else {
                                callback.onError(res.getErrorMessage());
                            }
                        } else {
                            callback.onError("HTTP " + response.code());
                        }
                    }
                    @Override
                    public void onFailure(Call<SendResponse> call, Throwable t) {
                        callback.onError(t.getMessage());
                    }
                });
    }

    public static void cancelPayment(Context context, String paymentToken,
                                     CallbackWithResult<Void> callback) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String jwt = prefs.getString("jwt", "");

        JsonObject cancelReq = new JsonObject();
        cancelReq.addProperty("paymentToken", paymentToken);

        RetrofitClient.getInstance().getSendService()
                .cancelPayment("Bearer " + jwt, VERSION, cancelReq)
                .enqueue(new retrofit2.Callback<JsonObject>() {
                    @Override
                    public void onResponse(Call<JsonObject> call, retrofit2.Response<JsonObject> response) {
                        if (response.isSuccessful()) {
                            callback.onSuccess(null);
                        } else {
                            callback.onError("Cancel error HTTP " + response.code());
                        }
                    }
                    @Override
                    public void onFailure(Call<JsonObject> call, Throwable t) {
                        callback.onError(t.getMessage());
                    }
                });
    }

    private static JsonObject buildOrderSlipSource(String tableNumber, List<Map<String, Object>> items, boolean isAlreadyOpen) {
        JsonObject sourceObj = new JsonObject();
        JsonObject invoiceObj = new JsonObject();

        JsonObject issuerObj = new JsonObject();
        issuerObj.addProperty("vatNumber", "000000000");
        issuerObj.addProperty("branch", 0);
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
        String issueDateTime = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(new Date());
        headerObj.addProperty("issueDate", issueDateTime);
        headerObj.addProperty("invoiceType", "8.6");
        headerObj.addProperty("currency", "EUR");

        headerObj.addProperty("openPointOfService", isAlreadyOpen ? 0 : 1);
        headerObj.addProperty("closePointOfService", 0);
        headerObj.addProperty("pointOfService", "Τραπέζι " + tableNumber);

        invoiceObj.add("invoiceHeader", headerObj);

        JsonArray detailsArray = new JsonArray();
        int lineNum = 1;
        for (Map<String, Object> item : items) {
            String name = (String) item.get("name");
            double price = ((Number) item.get("price")).doubleValue();
            int qty = ((Number) item.get("quantity")).intValue();
            double vatPct = item.containsKey("vatPercent") ?
                    ((Number) item.get("vatPercent")).doubleValue() : 13.0;

            double totalLine = price * qty;
            double netLine = totalLine / (1 + vatPct / 100.0);
            double vatLine = totalLine - netLine;

            JsonObject line = new JsonObject();
            line.addProperty("lineNumber", lineNum++);
            line.addProperty("quantity", qty);
            line.addProperty("entityName", name);
            line.addProperty("netValue", Math.round(netLine * 100.0) / 100.0);
            line.addProperty("vatCategory", vatPct == 0 ? 1 : 2);
            line.addProperty("vatAmount", Math.round(vatLine * 100.0) / 100.0);
            line.addProperty("vatPercent", vatPct);
            line.addProperty("totalValue", Math.round(totalLine * 100.0) / 100.0);
            line.addProperty("measurementUnit", 1);
            detailsArray.add(line);
        }
        invoiceObj.add("invoiceDetails", detailsArray);

        double totalNet = 0, totalVat = 0, totalVal = 0;
        for (int i = 0; i < detailsArray.size(); i++) {
            JsonObject d = detailsArray.get(i).getAsJsonObject();
            totalNet += d.get("netValue").getAsDouble();
            totalVat += d.get("vatAmount").getAsDouble();
            totalVal += d.get("totalValue").getAsDouble();
        }
        JsonObject summary = new JsonObject();
        summary.addProperty("totalNetValue", Math.round(totalNet * 100.0) / 100.0);
        summary.addProperty("totalVatAmount", Math.round(totalVat * 100.0) / 100.0);
        summary.addProperty("totalValue", Math.round(totalVal * 100.0) / 100.0);
        invoiceObj.add("invoiceSummary", summary);

        invoiceObj.add("paymentMethods", new JsonArray());

        sourceObj.add("invoice", invoiceObj);
        return sourceObj;
    }

    private static JsonObject buildFinalReceiptSource(String tableNumber, List<Map<String, Object>> items,
                                                      long originalMark, String originalExtId,
                                                      String posTransactionId, String posSignature,
                                                      double posAmount) {
        JsonObject sourceObj = new JsonObject();
        JsonObject invoiceObj = new JsonObject();

        JsonObject issuerObj = new JsonObject();
        issuerObj.addProperty("vatNumber", "000000000");
        issuerObj.addProperty("branch", 0);
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
        String issueDateTime = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(new Date());
        headerObj.addProperty("issueDate", issueDateTime);
        headerObj.addProperty("invoiceType", "11.2");
        headerObj.addProperty("currency", "EUR");
        headerObj.addProperty("pointOfService", "Τραπέζι " + tableNumber);
        invoiceObj.add("invoiceHeader", headerObj);

        JsonArray detailsArray = new JsonArray();
        int lineNum = 1;
        for (Map<String, Object> item : items) {
            String name = (String) item.get("name");
            double price = ((Number) item.get("price")).doubleValue();
            int qty = ((Number) item.get("quantity")).intValue();
            double vatPct = item.containsKey("vatPercent") ?
                    ((Number) item.get("vatPercent")).doubleValue() : 13.0;

            double totalLine = price * qty;
            double netLine = totalLine / (1 + vatPct / 100.0);
            double vatLine = totalLine - netLine;

            JsonObject line = new JsonObject();
            line.addProperty("lineNumber", lineNum++);
            line.addProperty("quantity", qty);
            line.addProperty("entityName", name);
            line.addProperty("netValue", Math.round(netLine * 100.0) / 100.0);
            line.addProperty("vatCategory", vatPct == 0 ? 1 : 2);
            line.addProperty("vatAmount", Math.round(vatLine * 100.0) / 100.0);
            line.addProperty("vatPercent", vatPct);
            line.addProperty("totalValue", Math.round(totalLine * 100.0) / 100.0);
            line.addProperty("measurementUnit", 1);
            detailsArray.add(line);
        }
        invoiceObj.add("invoiceDetails", detailsArray);

        double totalNet = 0, totalVat = 0, totalVal = 0;
        for (int i = 0; i < detailsArray.size(); i++) {
            JsonObject d = detailsArray.get(i).getAsJsonObject();
            totalNet += d.get("netValue").getAsDouble();
            totalVat += d.get("vatAmount").getAsDouble();
            totalVal += d.get("totalValue").getAsDouble();
        }
        JsonObject summary = new JsonObject();
        summary.addProperty("totalNetValue", Math.round(totalNet * 100.0) / 100.0);
        summary.addProperty("totalVatAmount", Math.round(totalVat * 100.0) / 100.0);
        summary.addProperty("totalValue", Math.round(totalVal * 100.0) / 100.0);
        invoiceObj.add("invoiceSummary", summary);

        if (originalMark > 0 && originalExtId != null && !originalExtId.isEmpty()) {
            JsonArray correlatedArray = new JsonArray();
            JsonObject corrObj = new JsonObject();
            corrObj.addProperty("extSystemId", originalExtId);
            corrObj.addProperty("mark", originalMark);
            correlatedArray.add(corrObj);
            invoiceObj.add("correlatedInvoices", correlatedArray);
        }

        JsonArray paymentsArray = new JsonArray();
        if (posTransactionId != null && !posTransactionId.isEmpty()) {
            JsonObject payObj = new JsonObject();
            payObj.addProperty("type", 7);
            payObj.addProperty("amount", Math.round(posAmount * 100.0) / 100.0);
            payObj.addProperty("signature", posSignature != null ? posSignature : "");
            payObj.addProperty("transactionId", posTransactionId);
            paymentsArray.add(payObj);
        }
        invoiceObj.add("paymentMethods", paymentsArray);

        sourceObj.add("invoice", invoiceObj);
        return sourceObj;
    }

    /**
     * ΑΠΟΣΤΟΛΗ ΑΚΥΡΩΤΙΚΟΥ 8.6 ΜΕΣΩ ListDetails API ΤΗΣ EPSILON DIGITAL
     * Αθροίζει αυτόματα τις πραγματικές αξίες των ανοιχτών ΜΑΡΚ για απόλυτη μαθηματική συμφωνία.
     */
    /**
     * ΑΠΟΣΤΟΛΗ ΑΚΥΡΩΤΙΚΟΥ 8.6 ΜΕΣΩ ListDetails API ΤΗΣ EPSILON DIGITAL
     * Αθροίζει αυτόματα τις πραγματικές αξίες των ανοιχτών ΜΑΡΚ για απόλυτη μαθηματική συμφωνία.
     */
    public static void cancelOrderSlip86(Context context, String tableNumber, List<Map<String, Object>> items, CallbackWithResult<SendResponse> callback) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String jwt = prefs.getString("jwt", "");
        String baseUrl = prefs.getString("baseUrl", "https://beta-epsilondigital.epsilonnet.gr/");
        String url = baseUrl + "api/send";

        new Thread(() -> {
            java.util.List<Long> marksToCancel = new java.util.ArrayList<>();
            double totalAmountToCancel = 0.0; // Το άθροισμα των ποσών προς ακύρωση

            // --- ΚΛΗΣΗ ΣΤΟ ListDetails API ---
            try {
                org.json.JSONObject listReqObj = new org.json.JSONObject();
                listReqObj.put("companyBranchCode", "0");
                listReqObj.put("pointOfService", "Τραπέζι " + tableNumber);
                listReqObj.put("refNumber", 1);

                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                okhttp3.RequestBody body = okhttp3.RequestBody.create(
                        okhttp3.MediaType.parse("application/json; charset=utf-8"),
                        listReqObj.toString()
                );
                okhttp3.Request listReq = new okhttp3.Request.Builder()
                        .url(baseUrl + "api/pointOfService/ListDetails")
                        .addHeader("Authorization", "Bearer " + jwt)
                        .addHeader("X-Version", VERSION)
                        .post(body)
                        .build();

                okhttp3.Response listResp = client.newCall(listReq).execute();
                if (listResp.isSuccessful() && listResp.body() != null) {
                    String respStr = listResp.body().string();
                    JsonObject respJson = new Gson().fromJson(respStr, JsonObject.class);
                    if (respJson.has("data") && respJson.get("data").isJsonArray()) {
                        JsonArray dataArr = respJson.getAsJsonArray("data");
                        for (int i = 0; i < dataArr.size(); i++) {
                            JsonObject posObj = dataArr.get(i).getAsJsonObject();
                            if (posObj.has("details") && posObj.get("details").isJsonArray()) {
                                JsonArray detailsArr = posObj.getAsJsonArray("details");
                                for (int j = 0; j < detailsArr.size(); j++) {
                                    JsonObject detObj = detailsArr.get(j).getAsJsonObject();
                                    if (detObj.has("mark") && !detObj.get("mark").isJsonNull()) {
                                        long apiMark = detObj.get("mark").getAsLong();
                                        if (apiMark > 0 && !marksToCancel.contains(apiMark)) {
                                            marksToCancel.add(apiMark);
                                            // Αθροίζουμε την ακριβή αξία του κάθε παραστατικού
                                            if (detObj.has("totalAmount") && !detObj.get("totalAmount").isJsonNull()) {
                                                totalAmountToCancel += detObj.get("totalAmount").getAsDouble();
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Αποτυχία ListDetails API: " + e.getMessage());
            }

            final double finalAmount = totalAmountToCancel;
            new Handler(Looper.getMainLooper()).post(() ->
                    proceedWithCancellation(context, url, jwt, tableNumber, marksToCancel, finalAmount, items, callback));
        }).start();
    }

    private static void proceedWithCancellation(Context context, String url, String jwt, String tableNumber,
                                                java.util.List<Long> marksToCancel, double totalAmount,
                                                List<Map<String, Object>> items,
                                                CallbackWithResult<SendResponse> callback) {
        // Αν το API δεν επέστρεψε ποσό (π.χ. fallback), υπολογίζουμε την αξία από τα τρέχοντα είδη του UI
        double amountToCancel = totalAmount;
        if (amountToCancel <= 0.0 && items != null) {
            for (Map<String, Object> item : items) {
                double p = item.containsKey("price") ? ((Number) item.get("price")).doubleValue() : 0.0;
                int q = item.containsKey("quantity") ? ((Number) item.get("quantity")).intValue() : 0;
                amountToCancel += (p * q);
            }
        }
        // Αν παραμένει 0, βάζουμε μια ελάχιστη τυπική αξία ασφαλείας
        if (amountToCancel <= 0.0) amountToCancel = 0.01;

        JsonObject source = buildCancellationSlipSource(tableNumber, marksToCancel, amountToCancel, items);
        String uniqueExternalId = "cancel_" + tableNumber + "_" + UUID.randomUUID().toString().substring(0, 12);

        SendRequest request = new SendRequest(uniqueExternalId, "eInvoicing", 0, source);

        Log.i(TAG, "Αποστολή Ακυρωτικού 8.6 (Τραπέζι " + tableNumber + ") με " + marksToCancel.size() + " ΜΑΡΚ | Συνολική Αξία Αναίρεσης: " + amountToCancel);

        sendCancellationWithPossibleRefresh(context, url, jwt, request, tableNumber, marksToCancel, amountToCancel, items, callback);
    }

    private static void sendCancellationWithPossibleRefresh(Context context, String url, String jwt,
                                                            SendRequest request, String tableNumber,
                                                            java.util.List<Long> marksToCancel, double amountToCancel,
                                                            List<Map<String, Object>> items,
                                                            CallbackWithResult<SendResponse> callback) {
        RetrofitClient.getInstance().getSendService()
                .sendInvoice(url, "Bearer " + jwt, VERSION, request)
                .enqueue(new Callback<SendResponse>() {
                    @Override
                    public void onResponse(Call<SendResponse> call, Response<SendResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            SendResponse res = response.body();
                            if (res.getErrorCode() == null && res.getErrorMessage() == null) {
                                pollForStatus(context, null, request.getExternalSystemId(), callback);
                            } else {
                                String errMsg = res.getErrorMessage() != null ? res.getErrorMessage() : "Άγνωστο σφάλμα Epsilon";
                                callback.onError(errMsg);
                            }
                        } else if (response.code() == 401) {
                            refreshCancellationAndRetry(context, tableNumber, marksToCancel, amountToCancel, items, callback);
                        } else {
                            callback.onError("HTTP " + response.code() + " κατά την αποστολή ακυρωτικού");
                        }
                    }
                    @Override
                    public void onFailure(Call<SendResponse> call, Throwable t) {
                        callback.onError(t.getMessage());
                    }
                });
    }

    private static void refreshCancellationAndRetry(Context context, String tableNumber,
                                                    java.util.List<Long> marksToCancel, double amountToCancel,
                                                    List<Map<String, Object>> items,
                                                    CallbackWithResult<SendResponse> callback) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String refreshToken = prefs.getString("refreshToken", null);
        String oldJwt = prefs.getString("jwt", null);

        if (refreshToken == null || oldJwt == null) {
            callback.onError("Αποτυχία ανανέωσης κωδικών για το ακυρωτικό");
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
                            prefs.edit().putString("jwt", loginData.getJwt()).putString("refreshToken", loginData.getJwtRefreshToken()).apply();

                            String newJwt = loginData.getJwt();
                            String baseUrl = prefs.getString("baseUrl", "https://beta-epsilondigital.epsilonnet.gr/");
                            JsonObject source = buildCancellationSlipSource(tableNumber, marksToCancel, amountToCancel, items);
                            SendRequest request = new SendRequest("cancel_" + tableNumber + "_" + UUID.randomUUID().toString().substring(0, 12), "eInvoicing", 0, source);
                            sendCancellationWithPossibleRefresh(context, baseUrl + "api/send", newJwt, request, tableNumber, marksToCancel, amountToCancel, items, callback);
                        } else {
                            callback.onError("Αποτυχία Refresh Token ακυρωτικού");
                        }
                    }
                    @Override
                    public void onFailure(Call<LoginResponse> call, Throwable t) {
                        callback.onError("Σφάλμα δικτύου: " + t.getMessage());
                    }
                });
    }

    private static JsonObject buildCancellationSlipSource(String tableNumber, java.util.List<Long> marksToCancel, double totalAmountToCancel, List<Map<String, Object>> originalItems) {
        JsonObject sourceObj = new JsonObject();
        JsonObject invoiceObj = new JsonObject();

        JsonObject issuerObj = new JsonObject();
        issuerObj.addProperty("vatNumber", "000000000");
        issuerObj.addProperty("branch", 0);
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
        String issueDateTime = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(new Date());
        headerObj.addProperty("issueDate", issueDateTime);
        headerObj.addProperty("invoiceType", "8.6");
        headerObj.addProperty("currency", "EUR");

        headerObj.addProperty("openPointOfService", 0);
        headerObj.addProperty("closePointOfService", 1);
        headerObj.addProperty("pointOfService", "Τραπέζι " + tableNumber);

        headerObj.addProperty("cancelConnected", true);
        headerObj.addProperty("IsCancelling", true);
        headerObj.addProperty("totalCancelDeliveryOrders", true);

        invoiceObj.add("invoiceHeader", headerObj);

        double detectedVatPercent = 13.0;
        int detectedVatCategory = 2;

        if (originalItems != null && !originalItems.isEmpty()) {
            for (Map<String, Object> item : originalItems) {
                if (item.containsKey("vatPercent")) {
                    Object vatObj = item.get("vatPercent");
                    if (vatObj instanceof Number) {
                        detectedVatPercent = ((Number) vatObj).doubleValue();
                        break;
                    }
                }
            }
        }

        if (detectedVatPercent == 24.0) detectedVatCategory = 1;
        else if (detectedVatPercent == 13.0) detectedVatCategory = 2;
        else detectedVatCategory = 2;

        // --- ΜΑΘΗΜΑΤΙΚΟΣ ΥΠΟΛΟΓΙΣΜΟΣ ΑΚΡΙΒΕΙΑΣ ---
        double netValue = totalAmountToCancel / (1.0 + (detectedVatPercent / 100.0));
        double vatAmount = totalAmountToCancel - netValue;

        netValue = Math.round(netValue * 100.0) / 100.0;
        vatAmount = Math.round(vatAmount * 100.0) / 100.0;
        double adjustedTotal = Math.round((netValue + vatAmount) * 100.0) / 100.0;

        JsonArray detailsArray = new JsonArray();
        JsonObject line = new JsonObject();
        line.addProperty("lineNumber", 1);
        line.addProperty("quantity", 1);
        line.addProperty("entityName", "ΣΥΝΟΛΙΚΗ ΑΝΑΙΡΕΣΗ ΠΑΡΑΓΓΕΛΙΑΣ");
        line.addProperty("netValue", netValue);
        line.addProperty("vatCategory", detectedVatCategory);
        line.addProperty("vatAmount", vatAmount);
        line.addProperty("vatPercent", detectedVatPercent);
        line.addProperty("totalValue", adjustedTotal);
        line.addProperty("measurementUnit", 1);
        detailsArray.add(line);

        invoiceObj.add("invoiceDetails", detailsArray);

        JsonObject summary = new JsonObject();
        summary.addProperty("totalNetValue", netValue);
        summary.addProperty("totalVatAmount", vatAmount);
        summary.addProperty("totalValue", adjustedTotal);
        invoiceObj.add("invoiceSummary", summary);

        if (marksToCancel != null && !marksToCancel.isEmpty()) {
            JsonArray correlatedArray = new JsonArray();
            for (Long m : marksToCancel) {
                if (m > 0) {
                    JsonObject corrObj = new JsonObject();
                    corrObj.addProperty("mark", m);
                    correlatedArray.add(corrObj);
                }
            }
            if (correlatedArray.size() > 0) {
                invoiceObj.add("correlatedInvoices", correlatedArray);
            }
        }

        invoiceObj.add("paymentMethods", new JsonArray());
        sourceObj.add("invoice", invoiceObj);
        return sourceObj;
    }
    /**
     * ΕΝΗΜΕΡΩΣΗ / ΑΦΑΙΡΕΣΗ ΕΙΔΩΝ ΠΑΡΑΓΓΕΛΙΑΣ 8.6 (Αναίρεση και Αντικατάσταση)
     * Ακυρώνει τα προηγούμενα ΜΑΡΚ του τραπεζιού και διαβιβάζει την τελική, διορθωμένη λίστα ειδών.
     */
    public static void updateOrderSlip86(Context context, String tableNumber, List<Map<String, Object>> updatedItems, CallbackWithResult<SendResponse> callback) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String jwt = prefs.getString("jwt", "");
        String baseUrl = prefs.getString("baseUrl", "https://beta-epsilondigital.epsilonnet.gr/");
        String url = baseUrl + "api/send";

        new Thread(() -> {
            java.util.List<Long> previousMarks = new java.util.ArrayList<>();
            double previousTotalAmount = 0.0;

            // 1. Αντλούμε τα ενεργά ΜΑΡΚ του τραπεζιού μέσω του ListDetails API
            try {
                org.json.JSONObject listReqObj = new org.json.JSONObject();
                listReqObj.put("companyBranchCode", "0");
                listReqObj.put("pointOfService", "Τραπέζι " + tableNumber);
                listReqObj.put("refNumber", 1);

                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                okhttp3.RequestBody body = okhttp3.RequestBody.create(
                        okhttp3.MediaType.parse("application/json; charset=utf-8"),
                        listReqObj.toString()
                );
                okhttp3.Request listReq = new okhttp3.Request.Builder()
                        .url(baseUrl + "api/pointOfService/ListDetails")
                        .addHeader("Authorization", "Bearer " + jwt)
                        .addHeader("X-Version", VERSION)
                        .post(body)
                        .build();

                okhttp3.Response listResp = client.newCall(listReq).execute();
                if (listResp.isSuccessful() && listResp.body() != null) {
                    JsonObject respJson = new Gson().fromJson(listResp.body().string(), JsonObject.class);
                    if (respJson.has("data") && respJson.get("data").isJsonArray()) {
                        JsonArray dataArr = respJson.getAsJsonArray("data");
                        for (int i = 0; i < dataArr.size(); i++) {
                            JsonObject posObj = dataArr.get(i).getAsJsonObject();
                            if (posObj.has("details") && posObj.get("details").isJsonArray()) {
                                JsonArray detailsArr = posObj.getAsJsonArray("details");
                                for (int j = 0; j < detailsArr.size(); j++) {
                                    JsonObject detObj = detailsArr.get(j).getAsJsonObject();
                                    if (detObj.has("mark") && !detObj.get("mark").isJsonNull()) {
                                        long m = detObj.get("mark").getAsLong();
                                        if (m > 0 && !previousMarks.contains(m)) {
                                            previousMarks.add(m);
                                            if (detObj.has("totalAmount") && !detObj.get("totalAmount").isJsonNull()) {
                                                previousTotalAmount += detObj.get("totalAmount").getAsDouble();
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "ListDetails API fetch failed during update: " + e.getMessage());
            }

            final double cancelAmount = previousTotalAmount;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (previousMarks.isEmpty()) {
                    // Αν δεν βρεθούν προηγούμενα ΜΑΡΚ, απλά διαβιβάζουμε την παραγγελία ως νέα
                    sendOrderSlip86(context, tableNumber, updatedItems, true, callback);
                } else {
                    // ΒΗΜΑ Α: Αποστολή ακυρωτικού για τα παλιά ΜΑΡΚ
                    cancelOrderSlip86(context, tableNumber, null, new CallbackWithResult<SendResponse>() {
                        @Override
                        public void onSuccess(SendResponse cancelResult) {
                            Log.i(TAG, "Παλαιά ΜΑΡΚ ακυρώθηκαν επιτυχώς. Διαβίβαση νέας διορθωμένης λίστας...");
                            // ΒΗΜΑ Β: Αποστολή του νέου 8.6 με την ενημερωμένη λίστα
                            sendOrderSlip86(context, tableNumber, updatedItems, true, callback);
                        }

                        @Override
                        public void onError(String message) {
                            callback.onError("Αποτυχία ακύρωσης παλαιών ειδών: " + message);
                        }
                    });
                }
            });
        }).start();
    }
}