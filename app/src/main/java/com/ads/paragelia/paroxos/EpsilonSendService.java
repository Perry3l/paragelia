package com.ads.paragelia.paroxos;

import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Url;

public interface EpsilonSendService {

    @POST
    Call<SendResponse> sendInvoice(
            @Url String dynamicUrl,
            @Header("Authorization") String authorization,
            @Header("X-Version") String version,
            @Body SendRequest request
    );

    @POST
    Call<SendResponse> getInvoiceStatus(
            @Url String dynamicUrl,
            @Header("Authorization") String authorization,
            @Header("X-Version") String version,
            @Body GetStatusRequest request
    );

    @POST
    Call<JsonObject> requestPayment(
            @Url String dynamicUrl,
            @Header("Authorization") String authorization,
            @Header("X-Version") String version,
            @Body JsonObject request
    );
}