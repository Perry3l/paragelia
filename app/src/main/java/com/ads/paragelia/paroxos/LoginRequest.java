package com.ads.paragelia.paroxos;

public class LoginRequest {
    private String subscriptionKey;
    private String email;
    private String password;

    public LoginRequest(String subscriptionKey, String email, String password) {
        this.subscriptionKey = subscriptionKey;
        this.email = email;
        this.password = password;
    }

    public String getSubscriptionKey() { return subscriptionKey; }
    public String getEmail() { return email; }
    public String getPassword() { return password; }
}
