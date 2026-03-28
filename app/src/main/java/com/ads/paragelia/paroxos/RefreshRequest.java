package com.ads.paragelia.paroxos;

public class RefreshRequest {
    private String token;
    private String refreshToken;

    public RefreshRequest(String token, String refreshToken) {
        this.token = token;
        this.refreshToken = refreshToken;
    }

    public String getToken() { return token; }
    public String getRefreshToken() { return refreshToken; }
}   