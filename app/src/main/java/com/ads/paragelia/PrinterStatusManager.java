package com.ads.paragelia;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.nfc.NfcAdapter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import com.zcs.sdk.DriverManager;
import com.zcs.sdk.Printer;
import com.zcs.sdk.SdkResult;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PrinterStatusManager {

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView tvClock, tvGps, tvNet, tvBattery, tvNfc, tvPrinter;

    private static final int COLOR_OK = Color.parseColor("#4CAF50");
    private static final int COLOR_WARNING = Color.parseColor("#FFC107");
    private static final int COLOR_ERROR = Color.parseColor("#F44336");
    private static final int COLOR_GRAY = Color.parseColor("#B0BEC5");

    private DriverManager driverManager;
    private Printer printer;

    public PrinterStatusManager(Activity activity) {
        this.activity = activity;
        driverManager = DriverManager.getInstance();
        printer = driverManager.getPrinter();
        initViews();
        startMonitoring();
    }

    private void initViews() {
        tvClock = activity.findViewById(R.id.status_clock);
        tvGps = activity.findViewById(R.id.status_gps);
        tvNet = activity.findViewById(R.id.status_net);
        tvBattery = activity.findViewById(R.id.status_battery);
        tvNfc = activity.findViewById(R.id.status_nfc);
        tvPrinter = activity.findViewById(R.id.status_printer);
    }

    public void startMonitoring() {
        Runnable statusRunnable = new Runnable() {
            @Override
            public void run() {
                updateClock();
                updateNetworkStatus();
                updateGpsStatus();
                updateBatteryStatus();
                updateNfcStatus();
                updatePrinterStatus();
                handler.postDelayed(this, 5000); // κάθε 5 δευτερόλεπτα
            }
        };
        handler.post(statusRunnable);
    }

    private void updateClock() {
        if (tvClock != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
            tvClock.setText(sdf.format(new Date()));
        }
    }

    private void updateNetworkStatus() {
        if (tvNet == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
            tvNet.setTextColor(isConnected ? COLOR_OK : COLOR_ERROR);
        } catch (Exception e) {
            tvNet.setTextColor(COLOR_GRAY);
        }
    }

    private void updateGpsStatus() {
        if (tvGps == null) return;
        try {
            LocationManager lm = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
            boolean isGpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
            tvGps.setTextColor(isGpsEnabled ? COLOR_OK : COLOR_ERROR);
        } catch (Exception e) {
            tvGps.setTextColor(COLOR_GRAY);
        }
    }

    private void updateBatteryStatus() {
        if (tvBattery == null) return;
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = activity.registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int batteryPct = (int) ((level / (float) scale) * 100);

                tvBattery.setText(batteryPct + "%");
                tvBattery.setTextColor(batteryPct > 20 ? COLOR_OK : COLOR_ERROR);

                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL;
                if (isCharging) tvBattery.setText("⚡ " + batteryPct + "%");
            }
        } catch (Exception e) {}
    }

    private void updateNfcStatus() {
        if (tvNfc == null) return;
        try {
            NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(activity);
            if (nfcAdapter == null) {
                tvNfc.setTextColor(COLOR_GRAY);
            } else if (nfcAdapter.isEnabled()) {
                tvNfc.setTextColor(COLOR_OK);
            } else {
                tvNfc.setTextColor(COLOR_ERROR);
            }
        } catch (Exception e) {
            tvNfc.setTextColor(COLOR_GRAY);
        }
    }

    private void updatePrinterStatus() {
        if (tvPrinter == null) return;
        executor.execute(() -> {
            int status = printer.getPrinterStatus();
            activity.runOnUiThread(() -> {
                if (status == SdkResult.SDK_OK) {
                    tvPrinter.setTextColor(COLOR_OK);
                    tvPrinter.setText("🖨️");
                } else if (status == SdkResult.SDK_PRN_STATUS_PAPEROUT) {
                    tvPrinter.setTextColor(COLOR_ERROR);
                    tvPrinter.setText("🖨️❌");
                } else if (status == SdkResult.SDK_PRN_STATUS_TOOHEAT) {
                    tvPrinter.setTextColor(COLOR_WARNING);
                    tvPrinter.setText("🖨️🔥");
                } else {
                    tvPrinter.setTextColor(COLOR_GRAY);
                    tvPrinter.setText("🖨️?");
                }
            });
        });
    }

    public void stopMonitoring() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }
}