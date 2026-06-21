package com.ads.paragelia;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class PrinterManager {
    private static PrinterManager instance;
    private List<PrinterDevice> printers = new ArrayList<>();
    private Context context;

    private PrinterManager(Context context) {
        this.context = context;
    }

    public static synchronized PrinterManager getInstance(Context context) {
        if (instance == null) instance = new PrinterManager(context);
        return instance;
    }

    public void addPrinter(PrinterDevice printer) {
        printers.add(printer);
    }

    public void removePrinter(PrinterDevice printer) {
        printers.remove(printer);
    }

    public List<PrinterDevice> getPrinters() {
        return printers;
    }

    public PrinterDevice getPrinterByName(String name) {
        for (PrinterDevice p : printers) {
            if (p.getName().equals(name)) return p;
        }
        return null;
    }

    public List<PrinterDevice> getPrintersByTarget(String target) {
        List<PrinterDevice> result = new ArrayList<>();
        for (PrinterDevice p : printers) {
            if (p.getTarget().equalsIgnoreCase(target)) result.add(p);
        }
        return result;
    }


    public void savePrintersConfig() {
        JSONArray arr = new JSONArray();
        for (PrinterDevice p : printers) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("name", p.getName());
                obj.put("type", p.getType());
                obj.put("target", p.getTarget());
                obj.put("imageMode", p.isImageMode()); // ΝΕΟ
                if (p instanceof NetworkPrinter) {
                    obj.put("ip", ((NetworkPrinter)p).getIp());
                    obj.put("port", ((NetworkPrinter)p).getPort());
                } else if (p instanceof UsbPrinter) {
                    obj.put("vid", ((UsbPrinter)p).getVendorId());
                    obj.put("pid", ((UsbPrinter)p).getProductId());
                }
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        SharedPreferences prefs = context.getSharedPreferences("printer_config", Context.MODE_PRIVATE);
        prefs.edit().putString("printers", arr.toString()).apply();
    }

    public void loadPrintersConfig() {
        SharedPreferences prefs = context.getSharedPreferences("printer_config", Context.MODE_PRIVATE);
        String json = prefs.getString("printers", "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String type = obj.getString("type");
                String name = obj.getString("name");
                String target = obj.getString("target");
                boolean imageMode = obj.optBoolean("imageMode", false);

                switch (type) {
                    case "IP":
                        String ip = obj.getString("ip");
                        int port = obj.getInt("port");
                        NetworkPrinter np = new NetworkPrinter(name, target, ip, port);
                        np.setImageMode(imageMode);
                        addPrinter(np);
                        break;
                    case "USB":
                        int vid = obj.optInt("vid", -1);
                        int pid = obj.optInt("pid", -1);
                        if (vid >= 0 && pid >= 0) {
                            UsbPrinterProfile profile = new UsbPrinterProfile();
                            profile.name = name;
                            profile.target = target;
                            profile.vid = vid;
                            profile.pid = pid;
                            profile.imageMode = imageMode;
                            pendingUsbProfiles.add(profile);
                        }
                        break;

                }
            }
        } catch (Exception ignored) {}
    }

    private List<UsbPrinterProfile> pendingUsbProfiles = new ArrayList<>();

    public static class UsbPrinterProfile {
        public String name;
        public String target;
        public int vid;
        public int pid;
        public boolean imageMode; // ΝΕΟ
    }

    public List<UsbPrinterProfile> getPendingUsbProfiles() {
        return pendingUsbProfiles;
    }

    public void clearPendingUsbProfile(UsbPrinterProfile profile) {
        pendingUsbProfiles.remove(profile);
    }
}