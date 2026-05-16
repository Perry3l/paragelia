package com.ads.paragelia.paroxos;

import com.google.gson.annotations.SerializedName;

public class RefreshRequest {

    @SerializedName("token")
    private String token;

    @SerializedName("refreshToken")
    private String refreshToken;

    public RefreshRequest(String token, String refreshToken) {
        this.token = token;
        this.refreshToken = refreshToken;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}