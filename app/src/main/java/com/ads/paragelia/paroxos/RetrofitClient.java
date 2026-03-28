package com.ads.paragelia.paroxos;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RetrofitClient {
    // 1. Το Base URL για το Login & Refresh Token
    private static final String AUTH_BASE_URL = "https://beta-myaccount.epsilonnet.gr/";

    // 2. Το Base URL για τις συναλλαγές, POS και Παραστατικά
    private static final String DIGITAL_BASE_URL = "https://beta-epsilondigital.epsilonnet.gr/";

    private static RetrofitClient instance;
    private EpsilonApiService apiService;
    private EpsilonSendService sendService;

    private RetrofitClient() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .build();

        // --- RETROFIT 1: Μόνο για Authentication (Login/Refresh) ---
        Retrofit authRetrofit = new Retrofit.Builder()
                .baseUrl(AUTH_BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        apiService = authRetrofit.create(EpsilonApiService.class);

        // --- RETROFIT 2: Για Παραστατικά & Πληρωμές (Send, RequestPayment κτλ) ---
        Retrofit digitalRetrofit = new Retrofit.Builder()
                .baseUrl(DIGITAL_BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        sendService = digitalRetrofit.create(EpsilonSendService.class);
    }

    public EpsilonSendService getSendService() {
        return sendService;
    }

    public EpsilonApiService getApiService() {
        return apiService;
    }

    public static synchronized RetrofitClient getInstance() {
        if (instance == null) {
            instance = new RetrofitClient();
        }
        return instance;
    }
}