package com.ads.paragelia;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.xml.transform.Result;

/**
 * Κεντρικός μηχανισμός καταγραφής σφαλμάτων και αναφοράς κατάστασης συσκευής.
 */
public class DeviceReporter {
    private static final String TAG = "DeviceReporter";
    private static DeviceReporter instance;
    private final Context context;
    private final String storeCode;
    private final String deviceName;

    private DeviceReporter(Context context) {
        this.context = context.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        this.deviceName = prefs.getString(SettingsActivity.KEY_DEVICE_NAME, Build.MODEL);
        this.storeCode = StoreConfig.getStoreCode(context);
    }

    public static synchronized DeviceReporter getInstance(Context context) {
        if (instance == null) {
            instance = new DeviceReporter(context);
        }
        return instance;
    }

    /**
     * Καταγράφει ένα σφάλμα (exception) με λεπτομέρειες.
     * @param tag       Ετικέτα (π.χ. "PaymentManager")
     * @param throwable Το exception
     */
    public void logError(String tag, Throwable throwable) {
        if (storeCode == null) return;

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        String stackTrace = sw.toString();

        Map<String, Object> errorData = new HashMap<>();
        errorData.put("tag", tag);
        errorData.put("message", throwable.getMessage());
        errorData.put("stackTrace", stackTrace);
        errorData.put("deviceName", deviceName);
        errorData.put("timestamp", ServerValue.TIMESTAMP);
        errorData.put("appVersion", getAppVersion());
        errorData.put("androidVersion", Build.VERSION.RELEASE);

        DatabaseReference ref = FirebaseHelper.getReference("device_logs/errors")
                .child(deviceName)
                .push();
        ref.setValue(errorData)
                .addOnFailureListener(e -> Log.e(TAG, "Failed to send error log", e));
    }

    /**
     * Καταγράφει ένα προειδοποιητικό ή πληροφοριακό μήνυμα.
     */
    public void logInfo(String tag, String message) {
        if (storeCode == null) return;
        Map<String, Object> infoData = new HashMap<>();
        infoData.put("tag", tag);
        infoData.put("message", message);
        infoData.put("deviceName", deviceName);
        infoData.put("timestamp", ServerValue.TIMESTAMP);
        infoData.put("level", "INFO");

        DatabaseReference ref = FirebaseHelper.getReference("device_logs/info")
                .child(deviceName)
                .push();
        ref.setValue(infoData);
    }

    /**
     * Αναφέρει την τρέχουσα κατάσταση της συσκευής (battery, memory, printer, network).
     * Καλείται περιοδικά από WorkManager.
     */
    public void reportDeviceStatus() {
        if (storeCode == null) return;

        Map<String, Object> status = new HashMap<>();
        status.put("timestamp", ServerValue.TIMESTAMP);
        status.put("deviceName", deviceName);
        status.put("appVersion", getAppVersion());

        // Μπαταρία
        android.os.BatteryManager bm = (android.os.BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            int batteryPct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            status.put("batteryPercent", batteryPct);
            boolean isCharging = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS) == android.os.BatteryManager.BATTERY_STATUS_CHARGING;
            status.put("isCharging", isCharging);
        }

        // Μνήμη
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            am.getMemoryInfo(mi);
            status.put("availMemMB", mi.availMem / (1024 * 1024));
            status.put("totalMemMB", mi.totalMem / (1024 * 1024));
        }

        // Δίκτυο
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null && activeNetwork.isConnected();
        status.put("networkConnected", isConnected);
        if (isConnected) {
            status.put("networkType", activeNetwork.getTypeName());
        }

        // Εκτυπωτής (αν υπάρχει built-in)
        try {
            com.zcs.sdk.DriverManager driverManager = com.zcs.sdk.DriverManager.getInstance();
            com.zcs.sdk.Printer printer = driverManager.getPrinter();
            if (printer != null) {
                int printerStatus = printer.getPrinterStatus();
                status.put("printerStatus", printerStatus); // 0 = OK, 2 = χωρίς χαρτί
                status.put("printerPaperOut", printerStatus == com.zcs.sdk.SdkResult.SDK_PRN_STATUS_PAPEROUT);
            }
        } catch (Exception e) {
            status.put("printerError", e.getMessage());
        }

        DatabaseReference statusRef = FirebaseHelper.getReference("device_status")
                .child(deviceName);
        statusRef.setValue(status);
    }

    private String getAppVersion() {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ---------- Worker για περιοδική αναφορά ----------
    public static class StatusWorker extends Worker {
        public StatusWorker(Context context, WorkerParameters params) {
            super(context, params);
        }

        @NonNull
        @Override
        public Result doWork() {
            DeviceReporter.getInstance(getApplicationContext()).reportDeviceStatus();
            return Result.success();
        }
    }

    /**
     * Ξεκινά την περιοδική αποστολή status (κάθε 2 ώρες).
     * Καλείται από το Application ή την MainActivity.
     */
    public static void startPeriodicStatusReporting(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest statusWork = new PeriodicWorkRequest.Builder(
                StatusWorker.class,
                2, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context).enqueue(statusWork);
    }
}