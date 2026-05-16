package com.ads.paragelia;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class FirebaseHelper {
    private static String storeCode;

    public static void init(String code) {
        storeCode = code;
    }

    public static String getStoreCode() {
        return storeCode;
    }

    public static DatabaseReference getReference(String path) {
        if (storeCode == null || storeCode.isEmpty()) {
            throw new IllegalStateException("Store code not initialized!");
        }
        return FirebaseDatabase.getInstance().getReference(storeCode + "/" + path);
    }
}