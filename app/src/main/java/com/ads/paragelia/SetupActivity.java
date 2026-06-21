package com.ads.paragelia;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.cardview.widget.CardView;

public class SetupActivity extends BaseActivity {

    public static final String PREFS_NAME = "AppPrefs";
    public static final String KEY_DEVICE_ROLE = "device_role";
    public static final String ROLE_PRINTER = "printer";
    public static final String ROLE_CLIENT = "client";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        CardView cardPrinter = findViewById(R.id.cardPrinter);
        CardView cardClient = findViewById(R.id.cardClient);

        cardPrinter.setOnClickListener(v -> promptStoreCode(ROLE_PRINTER));
        cardClient.setOnClickListener(v -> promptStoreCode(ROLE_CLIENT));
    }

    private void saveRole(String role) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(KEY_DEVICE_ROLE, role).apply();

        Toast.makeText(this, "Ρόλος αποθηκεύτηκε", Toast.LENGTH_SHORT).show();

        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
    private void promptStoreCode(String role) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        final EditText input = new EditText(this);
        input.setHint("Κωδικός καταστήματος");
        builder.setView(input);
        builder.setTitle("Εισαγωγή κωδικού");
        builder.setPositiveButton("OK", (dialog, which) -> {
            String code = input.getText().toString().trim();
            if (code.isEmpty()) {
                Toast.makeText(this, "Ο κωδικός είναι υποχρεωτικός", Toast.LENGTH_SHORT).show();
                return;
            }
            StoreConfig.saveStoreCode(this, code);
            FirebaseHelper.init(code);
            saveRole(role);
        });
        builder.setCancelable(false);
        builder.show();
    }
}