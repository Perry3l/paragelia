// NetworkPrinter.java
package com.ads.paragelia;

import android.graphics.Bitmap;
import android.util.Log;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class NetworkPrinter implements PrinterDevice {
    private String name, ip;
    private String target;
    private int port;
    private boolean imageMode = false;
    private Socket socket;
    private OutputStream outputStream;

    public NetworkPrinter(String name, String target, String ip, int port) {
        this.name = name;
        this.target = target;
        this.ip = ip;
        this.port = port;
    }

    @Override public void setTarget(String target) { this.target = target; }
    @Override public void setImageMode(boolean enabled) { this.imageMode = enabled; }
    @Override public boolean isImageMode() { return imageMode; }

    @Override public String getName() { return name; }
    @Override public String getType() { return "IP"; }
    @Override public String getTarget() { return target; }

    public String getIp() { return ip; }
    public int getPort() { return port; }

    @Override
    public boolean isAvailable() {
        try {
            Socket test = new Socket();
            test.connect(new InetSocketAddress(ip, port), 2000);
            test.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean connect() {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(ip, port), 3000);
            outputStream = socket.getOutputStream();
            return true;
        } catch (IOException e) {
            Log.e("NetworkPrinter", "Σφάλμα σύνδεσης", e);
            return false;
        }
    }

    @Override
    public void print(String text) {
        if (socket == null || !socket.isConnected()) {
            if (!connect()) return;
        }
        try {
            outputStream.write(text.getBytes("UTF-8"));
            outputStream.flush();
        } catch (IOException e) {
            Log.e("NetworkPrinter", "Σφάλμα εκτύπωσης", e);
            try { socket.close(); } catch (IOException ignored) {}
            socket = null;
        }
    }

    public void printBitmap(Bitmap bitmap) {
        if (!connect()) return;
        byte[] data = BitmapPrinterHelper.bitmapToEscPos(bitmap);
        try {
            outputStream.write(data);
            outputStream.flush();
        } catch (IOException e) {
            Log.e("NetworkPrinter", "Σφάλμα εκτύπωσης bitmap", e);
        }
    }

    @Override
    public void cutPaper() {
        if (socket != null && socket.isConnected()) {
            try {
                outputStream.write(new byte[]{0x1D, 0x56, 0x00});
                outputStream.flush();
            } catch (IOException e) {
                Log.e("NetworkPrinter", "Σφάλμα κοπής", e);
            }
        }
    }

    public void disconnect() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        socket = null;
    }
}