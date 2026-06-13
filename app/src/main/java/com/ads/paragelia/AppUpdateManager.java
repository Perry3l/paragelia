package com.ads.paragelia;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AppUpdateManager {
    private static final String TAG = "AppUpdateManager";
    private final Context context;
    private final String providerAuthority;
    private WeakReference<AlertDialog> dialogRef = null;
    private boolean isDestroyed = false;

    // Firebase Database URL
    private static final String FIREBASE_URL = "https://genikaserver-default-rtdb.europe-west1.firebasedatabase.app/";

    public AppUpdateManager(Context context) {
        this.context = context.getApplicationContext(); // αποφεύγουμε leak της Activity
        this.providerAuthority = context.getPackageName() + ".fileprovider";
    }

    /**
     * Καλείται από την Activity όταν καταστρέφεται, για να κλείσει τυχόν ανοιχτό dialog.
     */
    public void cleanup() {
        isDestroyed = true;
        if (dialogRef != null) {
            AlertDialog dialog = dialogRef.get();
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
            dialogRef = null;
        }
    }

    public void checkForUpdates() {
        Log.e("UPDATE_TEST", "checkForUpdates CALLED !!!");
        FirebaseDatabase db = FirebaseDatabase.getInstance(FIREBASE_URL);
        DatabaseReference updateRef = db.getReference("app_update");

        updateRef.get().addOnSuccessListener(snapshot -> {
            Log.d(TAG, "Snapshot exists: " + snapshot.exists());
            if (snapshot.exists()) {
                Object versionObj = snapshot.child("versionCode").getValue();
                String apkUrl = snapshot.child("apkUrl").getValue(String.class);
                Log.d(TAG, "versionObj = " + versionObj + " (class=" + (versionObj != null ? versionObj.getClass() : "null") + ")");
                Log.d(TAG, "apkUrl = " + apkUrl);

                Long newVersion = null;
                if (versionObj instanceof Long) newVersion = (Long) versionObj;
                else if (versionObj instanceof String) {
                    try {
                        newVersion = Long.parseLong((String) versionObj);
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Invalid versionCode string");
                    }
                }

                try {
                    PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                    Log.d(TAG, "Current versionCode = " + pInfo.versionCode);
                    if (newVersion != null && newVersion > pInfo.versionCode && apkUrl != null && !apkUrl.isEmpty()) {
                        Log.d(TAG, "Update available! Showing dialog.");
                        showUpdateDialog(apkUrl);
                    } else {
                        Log.d(TAG, "No update needed. newVersion=" + newVersion + " current=" + pInfo.versionCode);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Update check failed", e);
                }
            } else {
                Log.d(TAG, "No 'app_update' node found in Firebase.");
            }
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Firebase read failed", e);
        });
    }

    private void showUpdateDialog(String apkUrl) {
        // Το context μπορεί να είναι Application context, δεν μπορούμε να δείξουμε dialog
        if (!(context instanceof Activity)) {
            Log.w(TAG, "Context is not an Activity, downloading without dialog");
            downloadAndInstall(apkUrl);
            return;
        }

        Activity activity = (Activity) context;
        // Αν η Activity έχει ήδη καταστραφεί, μην εμφανίζεις dialog
        if (activity.isFinishing() || activity.isDestroyed()) {
            Log.w(TAG, "Activity is finishing or destroyed, cannot show dialog");
            return;
        }

        new Handler(Looper.getMainLooper()).post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;

            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle("Ενημέρωση Εφαρμογής");
            builder.setMessage("Υπάρχει διαθέσιμη νέα έκδοση. Πατήστε 'Ναι' για λήψη και εγκατάσταση.");
            builder.setPositiveButton("Ναι", (dialog, which) -> {
                Toast.makeText(context, "Λήψη νέας έκδοσης...", Toast.LENGTH_SHORT).show();
                downloadAndInstall(apkUrl);
            });
            builder.setCancelable(false);
            AlertDialog dialog = builder.show();
            dialogRef = new WeakReference<>(dialog);
        });
    }

    private void downloadAndInstall(String url) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder().url(url).build();
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) return;

                    File apkFile = new File(context.getExternalFilesDir(null), "update.apk");
                    if (apkFile.exists()) apkFile.delete();

                    try (InputStream is = response.body().byteStream();
                         FileOutputStream fos = new FileOutputStream(apkFile)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, read);
                        }
                        fos.flush();
                    }
                    // Αμέσως μετά το κατέβασμα, προχωράμε στην εγκατάσταση (χωρίς delay)
                    installApk(apkFile);
                }
            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
            }
        }).start();
    }

    private void installApk(File file) {
        // Αφαιρέθηκε η αποτυχημένη σιωπηρή εγκατάσταση ZCS
        installNormal(file);
    }

    private void installNormal(File file) {
        new Handler(Looper.getMainLooper()).post(() -> {
            // Ελέγχουμε αν η Activity (context) είναι ακόμα έγκυρη
            if (isDestroyed) {
                Log.w(TAG, "Manager already destroyed, cannot install");
                return;
            }
            if (!(context instanceof Activity)) {
                Log.w(TAG, "Context is not Activity, using generic install");
                performInstall(file);
                return;
            }
            Activity activity = (Activity) context;
            if (activity.isFinishing() || activity.isDestroyed()) {
                Log.w(TAG, "Activity is finishing or destroyed, aborting install");
                return;
            }

            // Προσπάθεια απελευθέρωσης lock task αν υπάρχει
            try {
                activity.stopLockTask();
            } catch (Exception e) {
                Log.w(TAG, "Could not stop lock task: " + e.getMessage());
            }

            performInstall(file);
        });
    }

    private void performInstall(File file) {
        try {
            Uri apkUri = FileProvider.getUriForFile(context, providerAuthority, file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Install Intent failed", e);
            Toast.makeText(context, "Σφάλμα κατά το άνοιγμα της εγκατάστασης.", Toast.LENGTH_SHORT).show();
        }
    }
}