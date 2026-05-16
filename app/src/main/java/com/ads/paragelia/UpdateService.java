package com.ads.paragelia;

import retrofit2.Call;
import retrofit2.http.GET;

public interface UpdateService {
    @GET("update.json")  // υποθέτουμε ότι το JSON είναι στη root του τομέα
    Call<UpdateResponse> checkUpdate();
}