package com.ads.paragelia.paroxos;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class SendResponse {

    @SerializedName("externalSystemId")
    private String externalSystemId;

    @SerializedName("processId")
    private String processId;

    @SerializedName("status")
    private int status;

    @SerializedName("errorCode")
    private String errorCode;

    @SerializedName("errorMessage")
    private String errorMessage;

    @SerializedName("errorCategory")
    private String errorCategory;

    @SerializedName("signing")
    private Signing signing;

    public static class Signing {
        @SerializedName("uid")
        private String uid;

        @SerializedName("mark")
        private long mark;

        @SerializedName("authenticationCode")
        private String authenticationCode;

        @SerializedName("qrCode")
        private String qrCode;

        @SerializedName("pdfUploaded")
        private boolean pdfUploaded;

        @SerializedName("pdfFileUrl")
        private String pdfFileUrl;

        @SerializedName("publishStatus")
        private int publishStatus;

        @SerializedName("paymentTokens")
        private List<PaymentToken> paymentTokens;

        public String getUid() { return uid; }
        public long getMark() { return mark; }
        public String getAuthenticationCode() { return authenticationCode; }
        public String getQrCode() { return qrCode; }
    }

    public static class PaymentToken {
        @SerializedName("timestamp")
        private String timestamp;

        @SerializedName("signature")
        private String signature;

        @SerializedName("amount")
        private double amount;

        @SerializedName("terminalId")
        private String terminalId;

        @SerializedName("uid")
        private String uid;
    }

    public String getExternalSystemId() { return externalSystemId; }
    public String getProcessId() { return processId; }
    public int getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public String getErrorCategory() { return errorCategory; }



    public String getUid() {
        return signing != null ? signing.getUid() : null;
    }

    public long getMark() {
        return signing != null ? signing.getMark() : 0;
    }

    public String getAuthenticationCode() {
        return signing != null ? signing.getAuthenticationCode() : null;
    }

    public String getQrCode() {
        return signing != null ? signing.getQrCode() : null;
    }
}