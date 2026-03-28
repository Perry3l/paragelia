package com.ads.paragelia.paroxos;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;


import com.ads.paragelia.R;

import java.io.IOException;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class paroxostest extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_paroxostest);

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
                        if (response.isSuccessful()) {
                            LoginResponse loginData = response.body();
                            if (loginData != null) {
                                saveTokens(loginData);
                                Toast.makeText(paroxostest.this, "Επιτυχής σύνδεση", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            // Ανάγνωση σώματος σφάλματος
                            try {
                                String errorBody = response.errorBody().string();
                                Log.e("API_ERROR", "Κωδικός: " + response.code() + ", Σώμα: " + errorBody);
                                Toast.makeText(paroxostest.this, "Σφάλμα: " + response.code(), Toast.LENGTH_SHORT).show();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    @Override
                    public void onFailure(Call<LoginResponse> call, Throwable t) {
                        Log.e("API_FAILURE", t.getMessage());
                        Toast.makeText(paroxostest.this, "Σφάλμα δικτύου: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void saveTokens(LoginResponse loginData) {
        SharedPreferences prefs = getSharedPreferences("my_prefs", MODE_PRIVATE);
        prefs.edit()
                .putString("jwt", loginData.getJwt())
                .putString("refreshToken", loginData.getJwtRefreshToken())
                .putString("baseUrl", loginData.getUrl1())
                .apply();

        Log.d("TOKENS", "JWT: " + loginData.getJwt());
        Log.d("TOKENS", "Base URL: " + loginData.getUrl1());
    }
}