package com.ads.paragelia.paroxos;

public class GetStatusRequest {
    private String processId;
    private String externalSystemId;

    public GetStatusRequest(String processId, String externalSystemId) {
        this.processId = processId;
        this.externalSystemId = externalSystemId;
    }
}