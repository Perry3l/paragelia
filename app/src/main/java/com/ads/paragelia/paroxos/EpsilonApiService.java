package com.ads.paragelia.paroxos;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface EpsilonApiService {
    @POST("/api/account/loginToSubscription")
    Call<LoginResponse> loginToSubscription(@Body LoginRequest request);

    // ΝΕΟ: Endpoint για το Refresh Token
    @POST("/api/token/refresh")
    Call<LoginResponse> refreshToken(@Body RefreshRequest request);
}