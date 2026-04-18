package com.ads.paragelia;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class PrinterSettingsActivity extends AppCompatActivity {

    private SwitchCompat switchTakeAway;
    private EditText etDeviceName;
    private Button btnSave, btnRestart;
    private SharedPreferences prefs;

    public static final String PREFS_NAME = "PrinterPrefs";
    public static final String KEY_TAKEAWAY_ENABLED = "takeaway_enabled";
    public static final String KEY_DEVICE_NAME = "device_name";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_printer_settings);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        switchTakeAway = findViewById(R.id.switchTakeAway);
        etDeviceName = findViewById(R.id.etDeviceName);
        btnSave = findViewById(R.id.btnSaveSettings);
        btnRestart = findViewById(R.id.btnRestartApp);

        // Φόρτωση αποθηκευμένων τιμών
        boolean takeawayEnabled = prefs.getBoolean(KEY_TAKEAWAY_ENABLED, false);
        String deviceName = prefs.getString(KEY_DEVICE_NAME, "Εκτυπωτής");

        switchTakeAway.setChecked(takeawayEnabled);
        etDeviceName.setText(deviceName);

        // Αποθήκευση του Take Away ΑΜΕΣΑ μόλις αλλάξει το switch
        switchTakeAway.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_TAKEAWAY_ENABLED, isChecked).apply();
            Toast.makeText(this, isChecked ? "Το Take Away ενεργοποιήθηκε" : "Το Take Away απενεργοποιήθηκε", Toast.LENGTH_SHORT).show();
        });

        // Το κουμπί Αποθήκευση τώρα αποθηκεύει μόνο το όνομα συσκευής
        btnSave.setOnClickListener(v -> {
            prefs.edit()
                    .putString(KEY_DEVICE_NAME, etDeviceName.getText().toString().trim())
                    .apply();
            Toast.makeText(this, "Το όνομα συσκευής αποθηκεύτηκε", Toast.LENGTH_SHORT).show();
        });

        btnRestart.setOnClickListener(v -> restartApp());
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
                        System.exit(0);
                    }, 300);
                })
                .setNegativeButton("Όχι", null)
                .show();
    }
}