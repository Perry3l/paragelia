package com.ads.paragelia;

import android.content.Context;
import android.content.SharedPreferences;

public class StoreConfig {
    private static final String PREFS_NAME = "store_prefs";
    private static final String KEY_STORE_CODE = "store_code";

    public static void saveStoreCode(Context context, String code) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_STORE_CODE, code).apply();
    }

    public static String getStoreCode(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_STORE_CODE, null);
    }
}