package com.ads.paragelia;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Διαχείριση αναβάθμισης εφαρμογής από τον server (Firebase).
 *
 * Κόμβος Firebase "app_update":
 *   versionCode  (long, υποχρεωτικό)  – ο νέος αριθμός έκδοσης
 *   apkUrl       (string, υποχρεωτικό) – το URL του APK
 *   versionName  (string, προαιρετικό) – π.χ. "1.0.9" για εμφάνιση
 *   notes        (string, προαιρετικό) – τι νέο υπάρχει (changelog)
 *   mandatory    (bool, προαιρετικό)   – αν true, δεν επιτρέπεται αναβολή
 *   apkSha256    (string, προαιρετικό) – checksum ακεραιότητας του APK
 */
public class AppUpdateManager {
    private static final String TAG = "AppUpdateManager";
    private static final String FIREBASE_URL =
            "https://genikaserver-default-rtdb.europe-west1.firebasedatabase.app/";

    // Αποτρέπει διπλή εμφάνιση όταν καλείται από πολλά activities
    private static boolean handlingUpdate = false;

    private final Activity activity;
    private final Context appContext;
    private final String providerAuthority;

    private AlertDialog activeDialog;
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressStatus;
    private TextView progressPercent;
    private volatile boolean cancelled = false;

    public AppUpdateManager(Activity activity) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();
        this.providerAuthority = activity.getPackageName() + ".fileprovider";
    }

    private static class UpdateInfo {
        long versionCode;
        String versionName;
        String apkUrl;
        String notes;
        String sha256;
        boolean mandatory;
    }

    // ------------------------------------------------------------- Έλεγχος

    public void checkForUpdates() {
        if (handlingUpdate) return; // ήδη ασχολούμαστε με ενημέρωση αλλού

        DatabaseReference ref = FirebaseDatabase.getInstance(FIREBASE_URL).getReference("app_update");
        ref.get().addOnSuccessListener(snapshot -> {
            UpdateInfo info = parse(snapshot);
            if (info == null) return;

            int current = currentVersionCode();
            if (info.versionCode > current) {
                handlingUpdate = true;
                showUpdateDialog(info);
            } else {
                Log.d(TAG, "Καμία ενημέρωση (τρέχουσα " + current + ", server " + info.versionCode + ")");
            }
        }).addOnFailureListener(e -> Log.e(TAG, "Αποτυχία ανάγνωσης app_update", e));
    }

    private UpdateInfo parse(DataSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) return null;

        String apkUrl = snapshot.child("apkUrl").getValue(String.class);
        if (apkUrl == null || apkUrl.trim().isEmpty()) return null;

        Object vObj = snapshot.child("versionCode").getValue();
        long versionCode;
        if (vObj instanceof Number) {
            versionCode = ((Number) vObj).longValue();
        } else if (vObj instanceof String) {
            try { versionCode = Long.parseLong((String) vObj); }
            catch (NumberFormatException e) { return null; }
        } else {
            return null;
        }

        UpdateInfo info = new UpdateInfo();
        info.versionCode = versionCode;
        info.apkUrl = apkUrl.trim();
        info.versionName = snapshot.child("versionName").getValue(String.class);
        info.notes = snapshot.child("notes").getValue(String.class);
        info.sha256 = snapshot.child("apkSha256").getValue(String.class);
        Object mand = snapshot.child("mandatory").getValue();
        info.mandatory = (mand instanceof Boolean && (Boolean) mand)
                || (mand instanceof String && "true".equalsIgnoreCase((String) mand));
        return info;
    }

    private int currentVersionCode() {
        try {
            PackageInfo p = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
            return p.versionCode;
        } catch (Exception e) {
            return Integer.MAX_VALUE; // σε σφάλμα, μην κατεβάζουμε τίποτα
        }
    }

    // -------------------------------------------------------- Διάλογος έκδοσης

    private void showUpdateDialog(UpdateInfo info) {
        if (activity.isFinishing() || activity.isDestroyed()) { handlingUpdate = false; return; }

        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) { handlingUpdate = false; return; }

            StringBuilder msg = new StringBuilder("Υπάρχει διαθέσιμη νέα έκδοση");
            if (info.versionName != null && !info.versionName.isEmpty()) {
                msg.append(" (").append(info.versionName).append(")");
            }
            msg.append(".");
            if (info.notes != null && !info.notes.trim().isEmpty()) {
                msg.append("\n\nΤι νέο υπάρχει:\n").append(info.notes.trim());
            }
            if (info.mandatory) {
                msg.append("\n\n⚠ Η ενημέρωση είναι υποχρεωτική για να συνεχίσετε.");
            }

            AlertDialog.Builder b = new AlertDialog.Builder(activity)
                    .setTitle("Ενημέρωση Εφαρμογής")
                    .setMessage(msg.toString())
                    .setCancelable(false)
                    .setPositiveButton("Λήψη & Εγκατάσταση", (d, w) -> startDownload(info));

            if (!info.mandatory) {
                b.setNegativeButton("Αργότερα", (d, w) -> { d.dismiss(); handlingUpdate = false; });
            }
            activeDialog = b.show();
        });
    }

    // --------------------------------------------------------------- Λήψη

    private void startDownload(UpdateInfo info) {
        cancelled = false;
        showProgressDialog(info);

        new Thread(() -> {
            File apkFile = new File(appContext.getExternalFilesDir(null), "update.apk");
            try {
                if (apkFile.exists() && !apkFile.delete()) {
                    Log.w(TAG, "Δεν διαγράφηκε το παλιό update.apk");
                }

                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder().url(info.apkUrl).build();
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        fail("Ο server επέστρεψε σφάλμα (" + response.code() + ")", info);
                        return;
                    }
                    ResponseBody body = response.body();
                    long total = body.contentLength();
                    setIndeterminate(total <= 0);

                    try (InputStream is = body.byteStream();
                         FileOutputStream fos = new FileOutputStream(apkFile)) {
                        byte[] buffer = new byte[16384];
                        long downloaded = 0;
                        int read;
                        while ((read = is.read(buffer)) != -1) {
                            if (cancelled) {
                                apkFile.delete();
                                return;
                            }
                            fos.write(buffer, 0, read);
                            downloaded += read;
                            if (total > 0) {
                                updateProgress((int) (downloaded * 100 / total), downloaded, total);
                            }
                        }
                        fos.flush();
                    }
                }

                // Προαιρετικός έλεγχος ακεραιότητας
                if (info.sha256 != null && !info.sha256.trim().isEmpty()) {
                    setStatus("Έλεγχος ακεραιότητας...");
                    String actual = sha256(apkFile);
                    if (!actual.equalsIgnoreCase(info.sha256.trim())) {
                        apkFile.delete();
                        fail("Το αρχείο ενημέρωσης είναι αλλοιωμένο και ακυρώθηκε.", info);
                        return;
                    }
                }

                dismissProgress();
                installApk(apkFile);

            } catch (Exception e) {
                Log.e(TAG, "Αποτυχία λήψης", e);
                apkFile.delete();
                if (!cancelled) fail("Αποτυχία λήψης: " + e.getMessage(), info);
            }
        }).start();
    }

    private void showProgressDialog(UpdateInfo info) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;

            View view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null);
            progressBar = view.findViewById(R.id.pbUpdate);
            progressStatus = view.findViewById(R.id.tvUpdateStatus);
            progressPercent = view.findViewById(R.id.tvUpdatePercent);

            AlertDialog.Builder b = new AlertDialog.Builder(activity)
                    .setTitle("Ενημέρωση")
                    .setView(view)
                    .setCancelable(false);
            if (!info.mandatory) {
                b.setNegativeButton("Ακύρωση", (d, w) -> {
                    cancelled = true;
                    handlingUpdate = false;
                });
            }
            progressDialog = b.show();
        });
    }

    private void setIndeterminate(boolean indeterminate) {
        activity.runOnUiThread(() -> {
            if (progressBar != null) progressBar.setIndeterminate(indeterminate);
        });
    }

    private void setStatus(String status) {
        activity.runOnUiThread(() -> {
            if (progressStatus != null) progressStatus.setText(status);
        });
    }

    private void updateProgress(int percent, long downloaded, long total) {
        activity.runOnUiThread(() -> {
            if (progressBar != null) {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(percent);
            }
            if (progressPercent != null) {
                progressPercent.setText(percent + "%  (" + mb(downloaded) + " / " + mb(total) + " MB)");
            }
        });
    }

    private void dismissProgress() {
        activity.runOnUiThread(() -> {
            if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
        });
    }

    private void fail(String message, UpdateInfo info) {
        activity.runOnUiThread(() -> {
            dismissProgress();
            if (activity.isFinishing() || activity.isDestroyed()) { handlingUpdate = false; return; }
            AlertDialog.Builder b = new AlertDialog.Builder(activity)
                    .setTitle("Σφάλμα ενημέρωσης")
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton("Επανάληψη", (d, w) -> startDownload(info));
            if (!info.mandatory) {
                b.setNegativeButton("Άκυρο", (d, w) -> { d.dismiss(); handlingUpdate = false; });
            }
            b.show();
        });
    }

    // ------------------------------------------------------------ Εγκατάσταση

    private void installApk(File file) {
        activity.runOnUiThread(() -> {
            // Έξοδος από kiosk (lock task) ώστε να εμφανιστεί η οθόνη εγκατάστασης
            try { activity.stopLockTask(); } catch (Exception ignored) {}

            try {
                Uri apkUri = FileProvider.getUriForFile(appContext, providerAuthority, file);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Αποτυχία ανοίγματος εγκατάστασης", e);
                Toast.makeText(appContext, "Σφάλμα κατά το άνοιγμα της εγκατάστασης.", Toast.LENGTH_LONG).show();
            } finally {
                handlingUpdate = false;
            }
        });
    }

    // ------------------------------------------------------------- Βοηθητικά

    private static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f", bytes / (1024.0 * 1024.0));
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = is.read(buf)) != -1) md.update(buf, 0, r);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Κλείσιμο τυχόν ανοιχτών διαλόγων (π.χ. στο onDestroy του activity). */
    public void cleanup() {
        cancelled = true;
        if (activeDialog != null && activeDialog.isShowing()) activeDialog.dismiss();
        if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
        activeDialog = null;
        progressDialog = null;
    }
}
