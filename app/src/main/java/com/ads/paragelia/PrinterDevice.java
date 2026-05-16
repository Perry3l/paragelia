package com.ads.paragelia;

public interface PrinterDevice {
    String getName();
    String getType();
    String getTarget();
    boolean isAvailable();
    void print(String text);
    void cutPaper();
    void setTarget(String target);  // ΝΕΑ ΜΕΘΟΔΟΣ
}