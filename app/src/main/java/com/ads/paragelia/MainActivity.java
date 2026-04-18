package com.ads.paragelia;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

// Προσθήκη των απαραίτητων κλάσεων για την επικοινωνία με την Epsilon
import com.ads.paragelia.paroxos.LoginRequest;
import com.ads.paragelia.paroxos.LoginResponse;
import com.ads.paragelia.paroxos.RetrofitClient;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends BaseActivity {
    private CardView cardTakeAway;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

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

        // --- ΚΛΗΣΗ ΣΤΟ EPSILON ΓΙΑ LOGIN ΑΘΟΡΥΒΑ ---
        // Κάνουμε Login μόνο αν η συσκευή είναι ο Client (σερβιτόρος)
        if (role.equals(SetupActivity.ROLE_CLIENT)) {
            performEpsilonLogin();
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
                Toast.makeText(MainActivity.this, "Αυτή η συσκευή είναι εκτυπωτής. Δεν μπορεί να δημιουργήσει παραγγελίες.", Toast.LENGTH_LONG).show();
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
        } else {
            cardTakeAway.setVisibility(View.GONE);
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
        });    }

    private void performEpsilonLogin() {
        // --- ΠΡΟΣΟΧΗ: ΑΥΤΟΙ ΕΙΝΑΙ ΟΙ ΔΟΚΙΜΑΣΤΙΚΟΙ (BETA) ΚΩΔΙΚΟΙ ---
        LoginRequest request = new LoginRequest(
                "1ADB7B09478F4C58892ADDBB23E0EF65", // Subscription Key
                "EA824C0CDE234554AFEE",             // API User (Email field)
                "Vvt8UTjQO_TO/CVkAcG9"             // Beta Password
        );

        RetrofitClient.getInstance().getApiService()
                .loginToSubscription(request)
                .enqueue(new Callback<LoginResponse>() {
                    @Override
                    public void onResponse(Call<LoginResponse> call, Response<LoginResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            LoginResponse loginData = response.body();

                            // Αποθήκευση των Tokens ώστε να τα βρει το ActiveTablesActivity
                            SharedPreferences myPrefs = getSharedPreferences("my_prefs", MODE_PRIVATE);
                            myPrefs.edit()
                                    .putString("jwt", loginData.getJwt())
                                    .putString("refreshToken", loginData.getJwtRefreshToken())
                                    .putString("baseUrl", loginData.getUrl1())
                                    .apply();

                            Log.d("EpsilonAuth", "Επιτυχής σύνδεση στο Epsilon. JWT αποθηκεύτηκε.");
                        } else {
                            Log.e("EpsilonAuth", "Σφάλμα σύνδεσης Epsilon: " + response.code());
                        }
                    }

                    @Override
                    public void onFailure(Call<LoginResponse> call, Throwable t) {
                        Log.e("EpsilonAuth", "Σφάλμα δικτύου Epsilon: " + t.getMessage());
                    }
                });
    }
}