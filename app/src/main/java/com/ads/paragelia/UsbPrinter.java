// UsbPrinter.java
package com.ads.paragelia;

import android.hardware.usb.UsbDevice;

public class UsbPrinter implements PrinterDevice {
    private UsbPrinterManager manager;
    private UsbDevice device;
    private String name;
    private String target;
    @Override public void setTarget(String target) { this.target = target; }

    public UsbPrinter(UsbPrinterManager manager, UsbDevice device, String name, String target) {
        this.manager = manager;
        this.device = device;
        this.name = name;
        this.target = target;
    }

    @Override public String getName() { return name; }
    @Override public String getType() { return "USB"; }
    @Override public String getTarget() { return target; }

    // 📌 Προσθήκη getters για αποθήκευση
    public int getVendorId() { return device.getVendorId(); }
    public int getProductId() { return device.getProductId(); }
    public String getDeviceName() { return device.getDeviceName(); }  // χρήσιμο για προβολή

    @Override
    public boolean isAvailable() {
        return manager != null && manager.isConnected();
    }

    @Override
    public void print(String text) {
        manager.printText(text);
    }

    @Override
    public void cutPaper() {
        manager.cutPaper();
    }
}