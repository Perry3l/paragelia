package com.ads.paragelia.paroxos;


public class SendRequest {
    private String externalSystemId;
    private String identifier;
    private int transmissionType;
    private Object source;

    public SendRequest(String externalSystemId, String identifier, int transmissionType, Object source) {
        this.externalSystemId = externalSystemId;
        this.identifier = identifier;
        this.transmissionType = transmissionType;
        this.source = source;
    }

    public String getExternalSystemId() { return externalSystemId; }
    public String getIdentifier() { return identifier; }
    public int getTransmissionType() { return transmissionType; }
    public Object getSource() { return source; }
}