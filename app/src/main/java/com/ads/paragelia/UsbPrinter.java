// UsbPrinter.java
package com.ads.paragelia;

import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;

public class UsbPrinter implements PrinterDevice {
    private UsbPrinterManager manager;
    private UsbDevice device;
    private String name;
    private String target;
    private boolean imageMode = false;   // ΝΕΟ

    @Override public void setTarget(String target) { this.target = target; }
    @Override public void setImageMode(boolean enabled) { this.imageMode = enabled; }
    @Override public boolean isImageMode() { return imageMode; }

    public UsbPrinter(UsbPrinterManager manager, UsbDevice device, String name, String target) {
        this.manager = manager;
        this.device = device;
        this.name = name;
        this.target = target;
    }
    public UsbDevice getUsbDevice() { return device; }
    @Override public String getName() { return name; }
    @Override public String getType() { return "USB"; }
    @Override public String getTarget() { return target; }

    public int getVendorId() { return device.getVendorId(); }
    public int getProductId() { return device.getProductId(); }
    public String getDeviceName() { return device.getDeviceName(); }

    @Override
    public boolean isAvailable() {
        return manager != null && manager.isConnected();
    }

    @Override
    public void print(String text) {
        manager.printText(text);
    }

    public void printBitmap(Bitmap bitmap) {
        if (manager == null) return;
        byte[] data = BitmapPrinterHelper.bitmapToEscPos(bitmap);
        manager.sendRaw(data);
    }

    @Override
    public void cutPaper() {
        manager.cutPaper();
    }
}