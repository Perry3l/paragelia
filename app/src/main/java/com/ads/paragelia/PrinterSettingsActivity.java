package com.ads.paragelia;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.Map;
import android.graphics.Bitmap;
import android.widget.ImageView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;

public class PrinterSettingsActivity extends BaseActivity {

    private SwitchCompat switchTakeAway, switchMemoryOverlay;
    private EditText etDeviceName;
    private Button btnSave, btnRestart, btnResolveAllMerges, btnRefreshMenu;
    private SharedPreferences prefs;

    public static final String PREFS_NAME = "PrinterPrefs";
    public static final String KEY_TAKEAWAY_ENABLED = "takeaway_enabled";
    public static final String KEY_DEVICE_NAME = "device_name";
    private static final String APK_URL = "https://firebasestorage.googleapis.com/v0/b/genikaserver.firebasestorage.app/o/app-debug.apk?alt=media&token=07d5c245-150c-473e-8411-06920941a8c3";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_printer_settings);
        showMemoryOverlay();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        switchTakeAway = findViewById(R.id.switchTakeAway);
        switchMemoryOverlay = findViewById(R.id.switchMemoryOverlay);
        etDeviceName = findViewById(R.id.etDeviceName);
        btnSave = findViewById(R.id.btnSaveSettings);
        btnRestart = findViewById(R.id.btnRestartApp);
        btnResolveAllMerges = findViewById(R.id.btnResolveAllMerges);
        btnRefreshMenu = findViewById(R.id.btnRefreshMenu);

        // Φόρτωση αποθηκευμένων τιμών
        boolean takeawayEnabled = prefs.getBoolean(KEY_TAKEAWAY_ENABLED, false);
        String deviceName = prefs.getString(KEY_DEVICE_NAME, "Εκτυπωτής");
        switchTakeAway.setChecked(takeawayEnabled);
        etDeviceName.setText(deviceName);

        // Memory overlay debug switch
        SharedPreferences prefsOverlay = getSharedPreferences("debug_prefs", MODE_PRIVATE);
        boolean overlayEnabled = prefsOverlay.getBoolean("show_memory_overlay", false);
        switchMemoryOverlay.setChecked(overlayEnabled);

        // Ακρόαση αλλαγών
        switchTakeAway.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_TAKEAWAY_ENABLED, isChecked).apply();
            Toast.makeText(this, isChecked ? "Το Take Away ενεργοποιήθηκε" : "Το Take Away απενεργοποιήθηκε", Toast.LENGTH_SHORT).show();
        });

        switchMemoryOverlay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefsOverlay.edit().putBoolean("show_memory_overlay", isChecked).apply();
            recreate(); // άμεση εφαρμογή
        });

        btnSave.setOnClickListener(v -> {
            prefs.edit()
                    .putString(KEY_DEVICE_NAME, etDeviceName.getText().toString().trim())
                    .apply();
            Toast.makeText(this, "Το όνομα συσκευής αποθηκεύτηκε", Toast.LENGTH_SHORT).show();
        });

        // Διάσπαση όλων των ενώσεων τραπεζιών
        btnResolveAllMerges.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Διάσπαση όλων των ενώσεων")
                    .setMessage("Θέλετε να διασπάσετε όλα τα συγχωνευμένα τραπέζια; " +
                            "Τα είδη θα παραμείνουν στα βασικά τραπέζια και τα υπόλοιπα θα ελευθερωθούν.")
                    .setPositiveButton("Ναι", (dialog, which) -> resolveAllMerges())
                    .setNegativeButton("Άκυρο", null)
                    .show();
        });

        // Ενημέρωση μενού (cache)
        btnRefreshMenu.setOnClickListener(v -> {
            MenuRepository.getInstance().refresh(this, () -> {
                runOnUiThread(() -> Toast.makeText(this, "Το μενού ενημερώθηκε επιτυχώς", Toast.LENGTH_SHORT).show());
            });
        });

        btnRestart.setOnClickListener(v -> restartApp());
        Button btnShowApkQr = findViewById(R.id.btnShowApkQr);
        btnShowApkQr.setOnClickListener(v -> showApkQrCode());
        String storeCode = StoreConfig.getStoreCode(this);
        if (storeCode == null || storeCode.isEmpty()) {
            // Ο χρήστης δεν έχει ρυθμίσει κωδικό → πήγαινε στο SetupActivity
            Intent intent = new Intent(this, SetupActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        // Τώρα μπορούμε να συνεχίσουμε κανονικά
        SharedPreferences prefs = getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE);
        String role = prefs.getString(SetupActivity.KEY_DEVICE_ROLE, null);
    }

    private void showApkQrCode() {
        Bitmap qrBitmap = generateQrCode(APK_URL, 512);
        if (qrBitmap != null) {
            ImageView ivQr = new ImageView(this);
            ivQr.setImageBitmap(qrBitmap);
            new AlertDialog.Builder(this)
                    .setTitle("Σκανάρετε για λήψη")
                    .setView(ivQr)
                    .setPositiveButton("OK", null)
                    .show();
        } else {
            Toast.makeText(this, "Αποτυχία δημιουργίας QR", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Βοηθητική μέθοδος για τη δημιουργία ενός QR code bitmap
     */
    private Bitmap generateQrCode(String text, int size) {
        BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
        try {
            return barcodeEncoder.encodeBitmap(text, BarcodeFormat.QR_CODE, size, size);
        } catch (WriterException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void resolveAllMerges() {
        DatabaseReference billsRef = FirebaseHelper.getReference("active_bills");
        billsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot tableSnap : snapshot.getChildren()) {
                    Map<String, Object> tableData = (Map<String, Object>) tableSnap.getValue();
                    if (tableData == null) continue;

                    if (tableData.containsKey("merged_to")) {
                        tableSnap.getRef().removeValue();
                    } else if (tableData.containsKey("current_order")) {
                        Map<String, Object> cur = (Map<String, Object>) tableData.get("current_order");
                        if (cur != null && cur.containsKey("merged_from")) {
                            tableSnap.getRef().child("current_order").child("merged_from").removeValue();
                        }
                    }
                }
                Toast.makeText(PrinterSettingsActivity.this, "Όλες οι ενώσεις διασπάστηκαν!", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(PrinterSettingsActivity.this, "Σφάλμα: " + error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void restartApp() {
        new AlertDialog.Builder(this)
                .setTitle("Επανεκκίνηση")
                .setMessage("Θέλετε να γίνει επανεκκίνηση της εφαρμογής;")
                .setPositiveButton("Ναι", (dialog, which) -> {
                    new android.os.Handler().postDelayed(() -> {
                        finishAffinity();
                        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(intent);
                        }

                    }, 300);
                })
                .setNegativeButton("Όχι", null)
                .show();
    }
}