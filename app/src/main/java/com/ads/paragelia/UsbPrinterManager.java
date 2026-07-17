package com.ads.paragelia;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.*;
import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class UsbPrinterManager {
    public static final String ACTION_USB_PERMISSION = "com.ads.paragelia.USB_PERMISSION";

    private Context context;
    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbDeviceConnection connection;
    private UsbInterface usbInterface;
    private UsbEndpoint outEndpoint;

    public UsbPrinterManager(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public List<UsbDevice> findPrinters() {
        List<UsbDevice> printers = new ArrayList<>();
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (device.getDeviceClass() == UsbConstants.USB_CLASS_PRINTER) {
                printers.add(device);
            } else {
                for (int i = 0; i < device.getInterfaceCount(); i++) {
                    if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
                        printers.add(device);
                        break;
                    }
                }
            }
        }
        return printers;
    }

    public boolean hasPermission(UsbDevice device) {
        return usbManager.hasPermission(device);
    }

    public void requestPermission(UsbDevice device) {
        PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
        usbManager.requestPermission(device, permissionIntent);
    }

    public boolean openPrinter(UsbDevice device) {
        this.usbDevice = device;
        if (!usbManager.hasPermission(device)) return false;

        connection = usbManager.openDevice(device);
        if (connection == null) return false;

        usbInterface = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
                usbInterface = intf;
                break;
            }
        }
        if (usbInterface == null) {
            connection.close();
            return false;
        }

        if (!connection.claimInterface(usbInterface, true)) {
            connection.close();
            return false;
        }

        outEndpoint = null;
        for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
            UsbEndpoint ep = usbInterface.getEndpoint(i);
            if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                outEndpoint = ep;
                break;
            }
        }
        if (outEndpoint == null) {
            connection.close();
            return false;
        }

        if (!initPrinter()) {
            connection.close();
            return false;
        }

        return true;
    }


    private boolean initPrinter() {
        if (connection == null || outEndpoint == null) return false;
        byte[] reset = {0x1B, 0x40};           // ESC @
        byte[] codePage = {0x1B, 0x74, 0x15}; // ESC t 0x15 = Windows-1253
        int r1 = connection.bulkTransfer(outEndpoint, reset, reset.length, 1000);
        int r2 = connection.bulkTransfer(outEndpoint, codePage, codePage.length, 1000);
        return r1 >= 0 && r2 >= 0;
    }

    public boolean printText(String text) {
        if (connection == null || outEndpoint == null) return false;
        try {
            byte[] data = text.getBytes("windows-1253");
            return sendRaw(data);
        } catch (UnsupportedEncodingException e) {
            // Fallback σε UTF-8
            return sendRaw(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    public boolean sendRaw(byte[] data) {
        if (connection == null || outEndpoint == null) return false;
        int transferred = connection.bulkTransfer(outEndpoint, data, data.length, 5000);
        return transferred >= 0;
    }

    public void cutPaper() {
        sendRaw(new byte[]{0x1D, 0x56, 0x00});
    }

    public void close() {
        if (connection != null) {
            if (usbInterface != null) connection.releaseInterface(usbInterface);
            connection.close();
            connection = null;
        }
    }

    public boolean isConnected() {
        return connection != null && outEndpoint != null;
    }
}