package com.ads.paragelia;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.widget.SwitchCompat;

public class SettingsActivity extends BaseActivity {

    private EditText etDeviceName;
    private Button btnSave;
    private SharedPreferences prefs;

    public static final String PREFS_NAME = "AppSettings";
    public static final String KEY_DEVICE_NAME = "device_name";
    private SwitchCompat switchTakeAway;
    private SharedPreferences prefsa;
    public static final String KEY_TAKEAWAY_ENABLED = "takeaway_enabled";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etDeviceName = findViewById(R.id.etDeviceName);
        btnSave = findViewById(R.id.btnSave);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Φόρτωση αποθηκευμένου ονόματος
        String savedName = prefs.getString(KEY_DEVICE_NAME, "");
        etDeviceName.setText(savedName);

        btnSave.setOnClickListener(v -> {
            String newName = etDeviceName.getText().toString().trim();
            if (newName.isEmpty()) {
                Toast.makeText(this, "Το όνομα δεν μπορεί να είναι κενό", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString(KEY_DEVICE_NAME, newName).apply();
            Toast.makeText(this, "Το όνομα αποθηκεύτηκε", Toast.LENGTH_SHORT).show();
            finish();
        });
        Button btnRefresh = findViewById(R.id.btnRefreshMenu);
        btnRefresh.setOnClickListener(v -> {
            MenuCache.clear(this);
            Toast.makeText(this, "Το μενού θα ενημερωθεί την επόμενη φορά που θα ανοίξετε τα προϊόντα", Toast.LENGTH_LONG).show();
            // Προαιρετικά, μπορούμε να κατεβάσουμε άμεσα αν θέλουμε
        });
        switchTakeAway = findViewById(R.id.switchTakeAway);
        prefsa = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        boolean takeawayEnabled = prefsa.getBoolean(KEY_TAKEAWAY_ENABLED, true); // default true
        switchTakeAway.setChecked(takeawayEnabled);

        switchTakeAway.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefsa.edit().putBoolean(KEY_TAKEAWAY_ENABLED, isChecked).apply();
            Toast.makeText(this, isChecked ? "Το Take Away ενεργοποιήθηκε" : "Το Take Away απενεργοποιήθηκε", Toast.LENGTH_SHORT).show();
        });
        Button btnRestart = findViewById(R.id.btnRestartApp);
        btnRestart.setOnClickListener(v -> restartApp());
    }
    private void restartApp() {
        new AlertDialog.Builder(this)
                .setTitle("Επανεκκίνηση")
                .setMessage("Θέλετε να γίνει επανεκκίνηση της εφαρμογής;")
                .setPositiveButton("Ναι", (dialog, which) -> {
                    // Μικρή καθυστέρηση για να κλείσει το dialog
                    new android.os.Handler().postDelayed(() -> {
                        // Τερματισμός της εφαρμογής
                        finishAffinity();
                        // Επανεκκίνηση μέσω του launcher
                        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(intent);
                        }
                        // Σκοτώνουμε τη διεργασία για να καθαρίσουν όλα
                        System.exit(0);
                    }, 300);
                })
                .setNegativeButton("Όχι", null)
                .show();
    }
}