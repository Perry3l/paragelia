package com.ads.paragelia;

import android.app.Application;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        String code = StoreConfig.getStoreCode(this);
        if (code != null) {
            FirebaseHelper.init(code);
        }
    }
}