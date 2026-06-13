package com.ads.paragelia;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.InputType;
import android.text.Layout;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.util.List;
import java.util.Map;

public class PrinterSettingsActivity extends BaseActivity {

    private SwitchCompat switchTakeAway, switchMemoryOverlay, switchOrderOnlyMode;
    private EditText etDeviceName;
    private Button btnSave, btnRestart, btnResolveAllMerges, btnRefreshMenu;
    private SharedPreferences prefs;

    public static final String PREFS_NAME = "PrinterPrefs";
    public static final String KEY_TAKEAWAY_ENABLED = "takeaway_enabled";
    public static final String KEY_DELIVERY_ENABLED = "delivery_enabled";
    public static final String KEY_DEVICE_NAME = "device_name";
    private static final String APK_URL = "https://firebasestorage.googleapis.com/v0/b/genikaserver.firebasestorage.app/o/app-release.apk?alt=media&token=2e036c32-d463-4d66-9371-541d90ed1b57";
    private static final String ORDER_ONLY_CODE = "1234";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_printer_settings);
        showMemoryOverlay();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        switchTakeAway = findViewById(R.id.switchTakeAway);
        switchMemoryOverlay = findViewById(R.id.switchMemoryOverlay);
        switchOrderOnlyMode = findViewById(R.id.switchOrderOnlyMode);
        etDeviceName = findViewById(R.id.etDeviceName);
        btnSave = findViewById(R.id.btnSaveSettings);
        btnRestart = findViewById(R.id.btnRestartApp);
        btnResolveAllMerges = findViewById(R.id.btnResolveAllMerges);
        btnRefreshMenu = findViewById(R.id.btnRefreshMenu);

        Button btnChangeTableCount = findViewById(R.id.btnChangeTableCount);
        btnChangeTableCount.setOnClickListener(v -> showChangeTableCountDialog());

        boolean takeawayEnabled = prefs.getBoolean(KEY_TAKEAWAY_ENABLED, false);
        String deviceName = prefs.getString(KEY_DEVICE_NAME, "Εκτυπωτής");
        switchTakeAway.setChecked(takeawayEnabled);
        etDeviceName.setText(deviceName);

        SharedPreferences prefsOverlay = getSharedPreferences("debug_prefs", MODE_PRIVATE);
        boolean overlayEnabled = prefsOverlay.getBoolean("show_memory_overlay", false);
        switchMemoryOverlay.setChecked(overlayEnabled);

        // Order‑only mode
        SharedPreferences orderPrefs = getSharedPreferences(SettingsActivity.PREFS_ORDER_MODE, MODE_PRIVATE);
        boolean orderOnlyEnabled = orderPrefs.getBoolean(SettingsActivity.KEY_ORDER_ONLY_MODE, false);
        switchOrderOnlyMode.setChecked(orderOnlyEnabled);

        switchTakeAway.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_TAKEAWAY_ENABLED, isChecked).apply();
            Toast.makeText(this, isChecked ? "Το Take Away ενεργοποιήθηκε" : "Το Take Away απενεργοποιήθηκε", Toast.LENGTH_SHORT).show();
        });
        Button btnManagePrinters = findViewById(R.id.btnManagePrinters);
        btnManagePrinters.setOnClickListener(v -> {
            Intent intent = new Intent(PrinterSettingsActivity.this, PrinterManagementActivity.class);
            startActivity(intent);
        });
        switchMemoryOverlay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefsOverlay.edit().putBoolean("show_memory_overlay", isChecked).apply();
            recreate();
        });

        switchOrderOnlyMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // Αναστρέφουμε προσωρινά για να μην αλλάξει χωρίς επιβεβαίωση
            switchOrderOnlyMode.setChecked(!isChecked);
            showOrderOnlyPasswordDialog(isChecked);
        });

        SwitchCompat switchDelivery = findViewById(R.id.switchDelivery);
        boolean deliveryEnabled = prefs.getBoolean(KEY_DELIVERY_ENABLED, false);
        switchDelivery.setChecked(deliveryEnabled);
        switchDelivery.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_DELIVERY_ENABLED, isChecked).apply();
            Toast.makeText(this, isChecked ? "Το Delivery ενεργοποιήθηκε" : "Το Delivery απενεργοποιήθηκε", Toast.LENGTH_SHORT).show();
        });

        btnSave.setOnClickListener(v -> {
            prefs.edit()
                    .putString(KEY_DEVICE_NAME, etDeviceName.getText().toString().trim())
                    .apply();
            Toast.makeText(this, "Το όνομα συσκευής αποθηκεύτηκε", Toast.LENGTH_SHORT).show();
        });

        btnResolveAllMerges.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Διάσπαση όλων των ενώσεων")
                    .setMessage("Θέλετε να διασπάσετε όλα τα συγχωνευμένα τραπέζια; " +
                            "Τα είδη θα παραμείνουν στα βασικά τραπέζια και τα υπόλοιπα θα ελευθερωθούν.")
                    .setPositiveButton("Ναι", (dialog, which) -> resolveAllMerges())
                    .setNegativeButton("Άκυρο", null)
                    .show();
        });

        btnRefreshMenu.setOnClickListener(v -> {
            MenuRepository.getInstance().refresh(this, () -> {
                runOnUiThread(() -> Toast.makeText(this, "Το μενού ενημερώθηκε επιτυχώς", Toast.LENGTH_SHORT).show());
            });
        });

        btnRestart.setOnClickListener(v -> restartApp());

        Button btnShowApkQr = findViewById(R.id.btnShowApkQr);
        btnShowApkQr.setOnClickListener(v -> showApkQrCode());

        Button btnChangeStoreCode = findViewById(R.id.btnChangeStoreCode);
        btnChangeStoreCode.setOnClickListener(v -> showChangeStoreCodeDialog());

        Button btnTestPrinters = findViewById(R.id.btnTestPrinters);
        btnTestPrinters.setOnClickListener(v -> testPrinters());

        String storeCode = StoreConfig.getStoreCode(this);
        if (storeCode == null || storeCode.isEmpty()) {
            Intent intent = new Intent(this, SetupActivity.class);
            startActivity(intent);
            finish();
        }
    }

    private void showChangeTableCountDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Αλλαγή αριθμού τραπεζιών");

        final EditText inputPassword = new EditText(this);
        inputPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        inputPassword.setHint("Κωδικός πρόσβασης");

        final EditText inputNewCount = new EditText(this);
        inputNewCount.setInputType(InputType.TYPE_CLASS_NUMBER);
        inputNewCount.setHint("Νέος αριθμός τραπεζιών (π.χ. 15)");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);
        layout.addView(inputPassword);
        layout.addView(inputNewCount);

        builder.setView(layout);

        builder.setPositiveButton("Αλλαγή", (dialog, which) -> {
            String password = inputPassword.getText().toString().trim();
            String newCountStr = inputNewCount.getText().toString().trim();

            if (!"admin123".equals(password)) {
                Toast.makeText(this, "Λάθος κωδικός πρόσβασης", Toast.LENGTH_SHORT).show();
                return;
            }
            if (newCountStr.isEmpty()) {
                Toast.makeText(this, "Εισάγετε αριθμό τραπεζιών", Toast.LENGTH_SHORT).show();
                return;
            }
            int newMax = Integer.parseInt(newCountStr);
            if (newMax < 1 || newMax > 30) {
                Toast.makeText(this, "Ο αριθμός πρέπει να είναι μεταξύ 1 και 30", Toast.LENGTH_SHORT).show();
                return;
            }

            SystemSettingsManager.getInstance().setMaxTables(newMax, () -> {
                runOnUiThread(() -> Toast.makeText(this,
                        "Ο μέγιστος αριθμός τραπεζιών άλλαξε σε " + newMax + " για όλες τις συσκευές",
                        Toast.LENGTH_LONG).show());
            });
        });
        builder.setNegativeButton("Ακύρωση", null);
        builder.show();
    }

    private void showOrderOnlyPasswordDialog(boolean requestedState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(requestedState ? "Ενεργοποίηση λειτουργίας μόνο παραγγελιών" : "Απενεργοποίηση λειτουργίας μόνο παραγγελιών");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("Κωδικός");
        builder.setView(input);

        builder.setPositiveButton("OK", (dialog, which) -> {
            String code = input.getText().toString();
            if (ORDER_ONLY_CODE.equals(code)) {
                SharedPreferences orderPrefs = getSharedPreferences(SettingsActivity.PREFS_ORDER_MODE, MODE_PRIVATE);
                orderPrefs.edit().putBoolean(SettingsActivity.KEY_ORDER_ONLY_MODE, requestedState).apply();
                switchOrderOnlyMode.setChecked(requestedState);
                Toast.makeText(this, requestedState ? "Λειτουργία μόνο παραγγελιών ΕΝΕΡΓΗ" : "Λειτουργία μόνο παραγγελιών ΑΠΕΝΕΡΓΟΠΟΙΗΘΗΚΕ", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Λάθος κωδικός", Toast.LENGTH_SHORT).show();
                switchOrderOnlyMode.setChecked(!requestedState);
            }
        });
        builder.setNegativeButton("Ακύρωση", (dialog, which) -> {
            switchOrderOnlyMode.setChecked(!requestedState);
        });
        builder.show();
    }

    private void testPrinters() {
        PrinterManager printerManager = PrinterManager.getInstance(this);
        List<PrinterDevice> printers = printerManager.getPrinters();

        if (printers.isEmpty()) {
            Toast.makeText(this, "Δεν υπάρχουν συνδεδεμένοι εκτυπωτές", Toast.LENGTH_SHORT).show();
            return;
        }

        String timestamp = android.text.format.DateFormat.format("dd/MM/yyyy HH:mm:ss", System.currentTimeMillis()).toString();

        for (PrinterDevice printer : printers) {
            if (!printer.isAvailable()) {
                Toast.makeText(this, "Ο εκτυπωτής " + printer.getName() + " δεν είναι διαθέσιμος", Toast.LENGTH_SHORT).show();
                continue;
            }

            String testText = "═══════════════════════\n" +
                    "   ΔΟΚΙΜΑΣΤΙΚΗ ΕΚΤΥΠΩΣΗ\n" +
                    "═══════════════════════\n" +
                    "Εκτυπωτής: " + printer.getName() + "\n" +
                    "Τύπος: " + printer.getType() + "\n" +
                    "Target: " + printer.getTarget() + "\n" +
                    "Ημερομηνία: " + timestamp + "\n" +
                    "═══════════════════════\n\n\n";

            new Thread(() -> {
                printer.print(testText);
                printer.cutPaper();
            }).start();

            Toast.makeText(this, "Εκτύπωση σε " + printer.getName(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showChangeStoreCodeDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Αλλαγή κωδικού καταστήματος");

        final EditText inputPassword = new EditText(this);
        inputPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        inputPassword.setHint("Κωδικός πρόσβασης");

        final EditText inputNewCode = new EditText(this);
        inputNewCode.setInputType(InputType.TYPE_CLASS_TEXT);
        inputNewCode.setHint("Νέος κωδικός καταστήματος");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);
        layout.addView(inputPassword);
        layout.addView(inputNewCode);

        builder.setView(layout);

        builder.setPositiveButton("Αλλαγή", (dialog, which) -> {
            String password = inputPassword.getText().toString().trim();
            String newCode = inputNewCode.getText().toString().trim();

            if (!"admin123".equals(password)) {
                Toast.makeText(this, "Λάθος κωδικός πρόσβασης", Toast.LENGTH_SHORT).show();
                return;
            }
            if (newCode.isEmpty()) {
                Toast.makeText(this, "Ο νέος κωδικός δεν μπορεί να είναι κενός", Toast.LENGTH_SHORT).show();
                return;
            }

            StoreConfig.saveStoreCode(this, newCode);
            FirebaseHelper.init(newCode);
            MenuCache.clear(this);

            Toast.makeText(this, "Ο κωδικός καταστήματος άλλαξε. Η εφαρμογή θα επανεκκινηθεί.", Toast.LENGTH_LONG).show();

            new android.os.Handler().postDelayed(() -> {
                finishAffinity();
                Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(intent);
                }
            }, 300);
        });
        builder.setNegativeButton("Ακύρωση", null);
        builder.show();
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