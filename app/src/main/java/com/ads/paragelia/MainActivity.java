package com.ads.paragelia;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.cardview.widget.CardView;

import com.ads.paragelia.paroxos.LoginRequest;
import com.ads.paragelia.paroxos.LoginResponse;
import com.ads.paragelia.paroxos.RetrofitClient;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends BaseActivity {
    private CardView cardTakeAway;
    private static final String BASE_URL = "https://genikaserver-default-rtdb.europe-west1.firebasedatabase.app/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        new AppUpdateManager(this).checkForUpdates();
        showMemoryOverlay();

        String storeCode = StoreConfig.getStoreCode(this);
        if (storeCode == null || storeCode.isEmpty()) {
            // Ο χρήστης δεν έχει εισάγει κωδικό καταστήματος
            Intent intent = new Intent(this, SetupActivity.class);
            startActivity(intent);
            finish();
            return;
        }
        SharedPreferences prefs = getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE);
        String role = prefs.getString(SetupActivity.KEY_DEVICE_ROLE, null);

        if (role == null) {

            Intent intent = new Intent(this, SetupActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        if (role.equals(SetupActivity.ROLE_PRINTER)) {

            Intent intent = new Intent(this, PrinterActivity.class);
            startActivity(intent);
            finish();
            return;
        }
        SharedPreferences orderPrefs = getSharedPreferences(SettingsActivity.PREFS_ORDER_MODE, MODE_PRIVATE);
        boolean orderOnlyMode = orderPrefs.getBoolean(SettingsActivity.KEY_ORDER_ONLY_MODE, false);

        if (orderOnlyMode) {
            CardView cardHistory = findViewById(R.id.cardHistory);
            CardView cardTakeAway = findViewById(R.id.cardTakeAway);
            cardHistory.setVisibility(View.GONE);
            cardTakeAway.setVisibility(View.GONE);
        }

        if (role.equals(SetupActivity.ROLE_CLIENT)) {
            performEpsilonLogin();
            MenuRepository.getInstance().loadMenu(this, null);
        }

        CardView cardNewOrder = findViewById(R.id.cardNewOrder);
        CardView cardTables = findViewById(R.id.cardTables);
        CardView cardHistory = findViewById(R.id.cardHistory);
        CardView cardReports = findViewById(R.id.cardReports);

        cardNewOrder.setOnClickListener(v -> {
            if (role != null && role.equals(SetupActivity.ROLE_CLIENT)) {
                Intent intent = new Intent(MainActivity.this, NewOrderActivity.class);
                startActivity(intent);
            } else if (role != null && role.equals(SetupActivity.ROLE_PRINTER)) {
                Toast.makeText(MainActivity.this,
                        "Αυτή η συσκευή είναι εκτυπωτής. Δεν μπορεί να δημιουργήσει παραγγελίες.",
                        Toast.LENGTH_LONG).show();
            } else {
                Intent intent = new Intent(MainActivity.this, SetupActivity.class);
                startActivity(intent);
                finish();
            }
        });

        cardTakeAway = findViewById(R.id.cardTakeAway);
        SharedPreferences prefsa = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        boolean takeawayEnabled = prefsa.getBoolean(SettingsActivity.KEY_TAKEAWAY_ENABLED, true);

        if (takeawayEnabled) {
            cardTakeAway.setVisibility(View.VISIBLE);
            cardTakeAway.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, TakeAwayActivity.class);
                startActivity(intent);
            });


            cardTakeAway.setOnLongClickListener(v -> {
                List<Map<String, Object>> mockItemsList = new ArrayList<>();

                Map<String, Object> item1 = new HashMap<>();
                item1.put("name", "Πίτα Γύρο Χοιρινό");
                item1.put("quantity", 2);
                item1.put("comment", "Χωρίς κρεμμύδι");

                Map<String, Object> item2 = new HashMap<>();
                item2.put("name", "Coca Cola 330ml");
                item2.put("quantity", 1);
                item2.put("comment", "");

                mockItemsList.add(item1);
                mockItemsList.add(item2);

                showNewEfoodOrderDialog(
                        "9d4a63b5-3e07",
                        "Γιώργος Παπαδόπουλος",
                        mockItemsList,
                        8.50
                );
                return true;
            });

        } else {
            cardTakeAway.setVisibility(View.GONE);
        }

        boolean deliveryEnabled = prefsa.getBoolean(SettingsActivity.KEY_DELIVERY_ENABLED, true);
        CardView cardDelivery = findViewById(R.id.cardDelivery); // χρησιμοποιήστε το σωστό ID
        if (deliveryEnabled) {
            cardDelivery.setVisibility(View.VISIBLE);
        } else {
            cardDelivery.setVisibility(View.GONE);
        }

        cardTables.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, ActiveTablesActivity.class);
            startActivity(intent);
        });

        cardHistory.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, HistoryActivity.class);
            startActivity(intent);
        });

        cardReports.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });


    }

    private void showNewEfoodOrderDialog(String orderId, String customerName, List<Map<String, Object>> itemsList, double totalAmount) {

        try {
            Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), notification);
            r.play();
        } catch (Exception e) {
            e.printStackTrace();
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("ΝΕΑ ΠΑΡΑΓΓΕΛΙΑ ONLINE!");

        StringBuilder itemsDisplay = new StringBuilder();
        for (Map<String, Object> item : itemsList) {
            String name = (String) item.get("name");
            int qty = (int) item.get("quantity");
            itemsDisplay.append(qty).append("x ").append(name).append("\n");
        }

        String message = "Πελάτης: " + customerName + "\n\n" +
                "Είδη:\n" + itemsDisplay.toString() + "\n" +
                "Σύνολο: " + String.format("%.2f", totalAmount) + "€";

        builder.setMessage(message);
        builder.setCancelable(false);

        builder.setPositiveButton("ΑΠΟΔΟΧΗ", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Map<String, Object> orderData = new HashMap<>();

                orderData.put("tableNumber", "ONLINE (" + customerName + ")");
                orderData.put("timestamp", System.currentTimeMillis());

                orderData.put("items", itemsList);

                DatabaseReference ordersRef = FirebaseHelper.getReference("orders");
                ordersRef.child("online_" + orderId).setValue(orderData)
                        .addOnSuccessListener(aVoid -> {
                            Toast.makeText(MainActivity.this, "Η παραγγελία εστάλη στον εκτυπωτή!", Toast.LENGTH_LONG).show();
                        })
                        .addOnFailureListener(e -> {
                            Toast.makeText(MainActivity.this, "Σφάλμα αποστολής στον εκτυπωτή", Toast.LENGTH_SHORT).show();
                            Log.e("ONLINE_PRINT", "Σφάλμα Firebase: " + e.getMessage());
                        });
            }
        });

        builder.setNegativeButton("ΑΠΟΡΡΙΨΗ", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Toast.makeText(MainActivity.this, "Η παραγγελία ΑΠΟΡΡΙΦΘΗΚΕ.", Toast.LENGTH_SHORT).show();
            }
        });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void performEpsilonLogin() {
        LoginRequest request = new LoginRequest(
                "1ADB7B09478F4C58892ADDBB23E0EF65",
                "EA824C0CDE234554AFEE",
                "Vvt8UTjQO_TO/CVkAcG9"
        );

        RetrofitClient.getInstance().getApiService()
                .loginToSubscription(request)
                .enqueue(new Callback<LoginResponse>() {
                    @Override
                    public void onResponse(Call<LoginResponse> call, Response<LoginResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            LoginResponse loginData = response.body();

                            SharedPreferences myPrefs = getSharedPreferences("my_prefs", MODE_PRIVATE);
                            myPrefs.edit()
                                    .putString("jwt", loginData.getJwt())
                                    .putString("refreshToken", loginData.getJwtRefreshToken())
                                    .putString("baseUrl", loginData.getUrl1())
                                    .apply();
                            Log.d("EpsilonAuth", "Επιτυχής σύνδεση στο Epsilon. JWT αποθηκεύτηκε.");
                        }
                    }

                    @Override
                    public void onFailure(Call<LoginResponse> call, Throwable t) {
                        Log.e("EpsilonAuth", "Σφάλμα δικτύου Epsilon: " + t.getMessage());
                    }
                });
    }
}