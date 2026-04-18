package com.ads.paragelia;

public class HistoryEntry {
    public String type;
    public String tableNumber;
    public double amount;
    public String paymentMethod; // "cash", "card", ή null
    public String deviceName;
    public long timestamp;
    public String details;
    public static final String TYPE_ORDER_COMPLETED = "order_completed";
    public static final String TYPE_PAYMENT_CASH = "payment_cash";
    public static final String TYPE_PAYMENT_CARD = "payment_card";
    public static final String TYPE_TABLE_CANCELLED = "table_cancelled";
    public static final String TYPE_TABLE_MOVED = "table_moved";
    public static final String TYPE_TABLE_MERGED = "table_merged";
    public static final String TYPE_PARTIAL_PAYMENT = "partial_payment";

    public HistoryEntry() {
        // απαραίτητος κενός constructor για Firebase
    }

    public HistoryEntry(String type, String tableNumber, double amount, String paymentMethod,
                        String deviceName, long timestamp, String details) {
        this.type = type;
        this.tableNumber = tableNumber;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.deviceName = deviceName;
        this.timestamp = timestamp;
        this.details = details;
    }
}