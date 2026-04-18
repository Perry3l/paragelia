package com.ads.paragelia;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.Map;

public class MenuCache {
    private static final String PREFS_NAME = "menu_cache";
    private static final String KEY_PRODUCTS = "products_json";
    private static final String KEY_LAST_UPDATE = "last_update";

    public static void saveProducts(Context context, Map<String, Object> products) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = new Gson().toJson(products);
        prefs.edit()
                .putString(KEY_PRODUCTS, json)
                .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                .apply();
    }

    public static Map<String, Object> loadProducts(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_PRODUCTS, null);
        if (json == null) return null;
        Type type = new TypeToken<Map<String, Object>>(){}.getType();
        return new Gson().fromJson(json, type);
    }

    public static long getLastUpdate(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_UPDATE, 0);
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply();
    }
}