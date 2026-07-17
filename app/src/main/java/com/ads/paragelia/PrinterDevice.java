package com.ads.paragelia;

public interface PrinterDevice {
    String getName();
    String getType();
    String getTarget();
    boolean isAvailable();
    /** Επιστρέφει true μόνο αν η εκτύπωση ολοκληρώθηκε επιτυχώς. */
    boolean print(String text);
    void cutPaper();
    void setTarget(String target);
    void setImageMode(boolean enabled);
    boolean isImageMode();
}