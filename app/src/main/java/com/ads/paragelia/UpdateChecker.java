package com.ads.paragelia;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private Context context;
    private String baseUrl;
    private DownloadManager downloadManager;
    private long downloadId;

    public UpdateChecker(Context context, String baseUrl) {
        this.context = context;
        this.baseUrl = baseUrl;
        downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    public void checkForUpdates() {
        String url = baseUrl;
        if (!url.endsWith("/")) url += "/";

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(url)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        UpdateService service = retrofit.create(UpdateService.class);
        service.checkUpdate().enqueue(new Callback<UpdateResponse>() {
            @Override
            public void onResponse(Call<UpdateResponse> call, Response<UpdateResponse> response) {
                if (!response.isSuccessful() || response.body() == null) {
                    Log.d(TAG, "Αδυναμία λήψης update.json");
                    return;
                }
                UpdateResponse update = response.body();
                int latestVersion = update.getVersionCode();
                String apkUrl = update.getApkUrl();

                int currentVersion = getCurrentVersionCode();
                if (latestVersion > currentVersion) {
                    showUpdateDialog(apkUrl);
                }
            }

            @Override
            public void onFailure(Call<UpdateResponse> call, Throwable t) {
                Log.e(TAG, "Αποτυχία ελέγχου ενημερώσεων", t);
            }
        });
    }

    // ΝΕΑ ΜΕΘΟΔΟΣ: Παίρνει το versionCode από το PackageManager (δεν χρειάζεται BuildConfig)
    private int getCurrentVersionCode() {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return 1;
        }
    }

    private void showUpdateDialog(String apkUrl) {
        new AlertDialog.Builder(context)
                .setTitle("Διαθέσιμη ενημέρωση")
                .setMessage("Βρέθηκε νέα έκδοση. Θέλετε να την κατεβάσετε;")
                .setPositiveButton("Λήψη", (dialog, which) -> startDownload(apkUrl))
                .setNegativeButton("Αργότερα", null)
                .show();
    }

    private void startDownload(String apkUrl) {
        String fileName = "Paragelia_update.apk";
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
        request.setTitle("Λήψη ενημέρωσης");
        request.setDescription("Παρακαλώ περιμένετε...");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setRequiresCharging(false);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
        }

        downloadId = downloadManager.enqueue(request);

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(onDownloadComplete, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(onDownloadComplete, filter);
        }

        Toast.makeText(context, "Η λήψη ξεκίνησε...", Toast.LENGTH_SHORT).show();
    }

    private BroadcastReceiver onDownloadComplete = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id == downloadId) {
                installApk();
                ctx.unregisterReceiver(this);
            }
        }
    };

    private void installApk() {
        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Paragelia_update.apk");
        if (!file.exists()) {
            Toast.makeText(context, "Το αρχείο δεν βρέθηκε", Toast.LENGTH_SHORT).show();
            return;
        }

        Uri apkUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            apkUri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", file);
        } else {
            apkUri = Uri.fromFile(file);
        }

        Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(installIntent);
    }
}