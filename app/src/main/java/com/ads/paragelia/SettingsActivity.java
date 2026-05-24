package com.ads.paragelia;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
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

public class SettingsActivity extends BaseActivity {

    private EditText etDeviceName;
    private Button btnSave;
    private SharedPreferences prefs;

    public static final String PREFS_NAME = "AppSettings";
    public static final String KEY_DEVICE_NAME = "device_name";
    private SwitchCompat switchTakeAway;
    private SharedPreferences prefsa;
    public static final String KEY_TAKEAWAY_ENABLED = "takeaway_enabled";

    // Order-Only Mode constants
    public static final String PREFS_ORDER_MODE = "order_mode_prefs";
    public static final String KEY_ORDER_ONLY_MODE = "order_only_mode";
    private static final String ORDER_ONLY_CODE = "1234";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etDeviceName = findViewById(R.id.etDeviceName);
        btnSave = findViewById(R.id.btnSave);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        showMemoryOverlay();

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

        Button btnRefreshMenu = findViewById(R.id.btnRefreshMenu);
        btnRefreshMenu.setOnClickListener(v -> {
            MenuRepository.getInstance().refresh(this, () -> {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Το μενού ενημερώθηκε επιτυχώς", Toast.LENGTH_SHORT).show();
                });
            });
        });

        SwitchCompat switchMemoryOverlay = findViewById(R.id.switchMemoryOverlay);
        SharedPreferences prefsOverlay = getSharedPreferences("debug_prefs", MODE_PRIVATE);
        boolean overlayEnabled = prefsOverlay.getBoolean("show_memory_overlay", false);
        switchMemoryOverlay.setChecked(overlayEnabled);

        switchMemoryOverlay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefsOverlay.edit().putBoolean("show_memory_overlay", isChecked).apply();
            recreate();
        });

        switchTakeAway = findViewById(R.id.switchTakeAway);
        prefsa = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        boolean takeawayEnabled = prefsa.getBoolean(KEY_TAKEAWAY_ENABLED, true);
        switchTakeAway.setChecked(takeawayEnabled);

        switchTakeAway.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefsa.edit().putBoolean(KEY_TAKEAWAY_ENABLED, isChecked).apply();
            Toast.makeText(this, isChecked ? "Το Take Away ενεργοποιήθηκε" : "Το Take Away απενεργοποιήθηκε", Toast.LENGTH_SHORT).show();
        });

        Button btnResolveAll = findViewById(R.id.btnResolveAllMerges);
        btnResolveAll.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Διάσπαση όλων των ενώσεων")
                    .setMessage("Θέλετε να διασπάσετε όλα τα συγχωνευμένα τραπέζια; " +
                            "Τα είδη θα παραμείνουν στα βασικά τραπέζια και τα υπόλοιπα θα ελευθερωθούν.")
                    .setPositiveButton("Ναι", (dialog, which) -> resolveAllMerges())
                    .setNegativeButton("Άκυρο", null)
                    .show();
        });

        Button btnRestart = findViewById(R.id.btnRestartApp);
        btnRestart.setOnClickListener(v -> restartApp());

        // ----- NEW: Order-Only Mode Toggle Button -----
        Button btnToggleOrderMode = findViewById(R.id.btnToggleOrderMode);
        btnToggleOrderMode.setOnClickListener(v -> {
            SharedPreferences orderPrefs = getSharedPreferences(PREFS_ORDER_MODE, MODE_PRIVATE);
            boolean isEnabled = orderPrefs.getBoolean(KEY_ORDER_ONLY_MODE, false);

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(isEnabled ? "Απενεργοποίηση Λειτουργίας Μόνο Παραγγελιών" : "Ενεργοποίηση Λειτουργίας Μόνο Παραγγελιών");

            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setHint("Κωδικός");
            builder.setView(input);

            builder.setPositiveButton("OK", (dialog, which) -> {
                String code = input.getText().toString();
                if (ORDER_ONLY_CODE.equals(code)) {
                    boolean newState = !isEnabled;
                    orderPrefs.edit().putBoolean(KEY_ORDER_ONLY_MODE, newState).apply();
                    Toast.makeText(this, newState ? "Λειτουργία μόνο παραγγελιών ΕΝΕΡΓΗ" : "Λειτουργία μόνο παραγγελιών ΑΠΕΝΕΡΓΟΠΟΙΗΘΗΚΕ", Toast.LENGTH_LONG).show();
                    restartApp();
                } else {
                    Toast.makeText(this, "Λάθος κωδικός", Toast.LENGTH_SHORT).show();
                }
            });
            builder.setNegativeButton("Ακύρωση", null);
            builder.show();
        });
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
                Toast.makeText(SettingsActivity.this, "Όλες οι ενώσεις διασπάστηκαν!", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(SettingsActivity.this, "Σφάλμα: " + error.getMessage(), Toast.LENGTH_SHORT).show();
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