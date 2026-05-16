package com.ads.paragelia.paroxos;

import android.content.Intent;

import org.json.JSONObject;

import java.util.Locale;

public class EpayHelper {

    private static final String TARGET_PACKAGE = "gr.epayworldwide.softpos";
    private static final String TARGET_ACTION = "gr.epayworldwide.softpos.SOFTPOS";
    private static final String CONFIG_KEY = "gr.epayworldwide.softpos.CONFIGURATION";

    private static String formatEpayTimestamp(String rawTimestamp) {
        if (rawTimestamp == null) return "";
        try {
            String clean = rawTimestamp;
            if (clean.contains("+")) clean = clean.substring(0, clean.indexOf("+"));
            if (clean.contains(".")) {
                String[] parts = clean.split("\\.");
                String ms = parts[1];
                if (ms.length() > 3) ms = ms.substring(0, 3);
                else while (ms.length() < 3) ms += "0";
                clean = parts[0] + "." + ms;
            }
            return clean;
        } catch (Exception e) {
            return rawTimestamp;
        }
    }

    public static Intent createSaleIntent(String myPackageName,
                                          double totalAmount,
                                          double netAmount,
                                          double vatAmount,
                                          String orderRef,
                                          String uid,
                                          String localSignature,
                                          String providerId,
                                          String timestamp,
                                          String paymentMethod,
                                          String tid) {
        try {
            String totalStr = String.format(Locale.US, "%.2f", totalAmount);
            String netStr = String.format(Locale.US, "%.2f", netAmount);
            String vatStr = String.format(Locale.US, "%.2f", vatAmount);
            String safeTimestamp = formatEpayTimestamp(timestamp);

            JSONObject extras = new JSONObject();
            extras.put("uid", uid);
            extras.put("signature", localSignature);
            extras.put("net_value", netStr);
            extras.put("vat_value", vatStr);
            extras.put("total_value", totalStr);
            extras.put("payment_amount", totalStr);
            extras.put("tid", tid);
            extras.put("provider_id", providerId);
            extras.put("time_stamp", safeTimestamp);

            JSONObject data = new JSONObject();
            data.put("amount", totalStr);
            data.put("tip", "0.00");
            data.put("order_reference", orderRef);
            data.put("show_result", "false");

            data.put("payment_method_type", paymentMethod);
            data.put("extras", extras);

            JSONObject config = new JSONObject();
            config.put("operation", "sale");
            config.put("data", data);

            JSONObject root = new JSONObject();
            root.put("payload", config.toString());
            root.put("signature", "");

            String finalJson = root.toString().replace("\\/", "/");


            Intent intent = new Intent(TARGET_ACTION);
            intent.setPackage(TARGET_PACKAGE);
            intent.putExtra(CONFIG_KEY, finalJson);

            return intent;

        } catch (Exception e) {
            return null;
        }
    }
    public static Intent createSaleIntentForToken(String myPackageName, double totalAmount,
                                                  String orderRef, String paymentToken) {
        try {
            JSONObject data = new JSONObject();
            data.put("amount", String.format(Locale.US, "%.2f", totalAmount));
            data.put("tip", "0.00");
            data.put("order_reference", orderRef);
            data.put("payment_token", paymentToken);  // ΠΡΟΣΘΗΚΗ

            JSONObject extras = new JSONObject();
            extras.put("provider_id", "epsilon");     // αν χρειάζεται
            data.put("extras", extras);

            JSONObject config = new JSONObject();
            config.put("operation", "sale");
            config.put("data", data);

            JSONObject root = new JSONObject();
            root.put("payload", config.toString());
            root.put("signature", "");

            Intent intent = new Intent(TARGET_ACTION);
            intent.setPackage(TARGET_PACKAGE);
            intent.putExtra(CONFIG_KEY, root.toString());
            return intent;
        } catch (Exception e) {
            return null;
        }
    }
}