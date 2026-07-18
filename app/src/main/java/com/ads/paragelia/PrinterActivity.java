package com.ads.paragelia;

import static com.ads.paragelia.UsbPrinterManager.ACTION_USB_PERMISSION;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.text.Layout;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.zcs.sdk.DriverManager;
import com.zcs.sdk.Printer;
import com.zcs.sdk.SdkResult;
import com.zcs.sdk.Sys;

import java.util.List;
import java.util.Map;

public class PrinterActivity extends BaseActivity {
    private java.util.concurrent.ExecutorService printExecutor;
    private static final String TAG = "PrinterActivity";
    private TextView tvStatus;
    private DatabaseReference ordersRef, receiptsRef;
    private ChildEventListener orderListener, receiptListener;
    private PrinterStatusManager statusManager;
    private DriverManager mDriverManager;
    private Sys mSys;
    private Printer mPrinter;
    private PrinterManager printerManager;
    private UsbPrinterManager usbPrinterManager;
    public Printer getPrinter() { return mPrinter; }

    private RecyclerView rvPrinters;
    private PrinterAdapter printerAdapter;
    private boolean usbReceiverRegistered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_printer);
        new AppUpdateManager(this).checkForUpdates();
        showMemoryOverlay();

        tvStatus = findViewById(R.id.tvStatus);
        rvPrinters = findViewById(R.id.rvPrinters);

        // Ενσωματωμένος εκτυπωτής ZCS
        mDriverManager = DriverManager.getInstance();
        mSys = mDriverManager.getBaseSysDevice();
        mPrinter = mDriverManager.getPrinter();
        statusManager = new PrinterStatusManager(this);
        int initRet = mSys.sdkInit();
        if (initRet != SdkResult.SDK_OK) {
            mSys.sysPowerOn();
            try { Thread.sleep(1000); } catch (InterruptedException e) { e.printStackTrace(); }
            initRet = mSys.sdkInit();
            if (initRet != SdkResult.SDK_OK) {
                Toast.makeText(this, "Αποτυχία αρχικοποίησης SDK", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }

        SharedPreferences prefs = getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE);
        String role = prefs.getString(SetupActivity.KEY_DEVICE_ROLE, null);
        if (role == null || !role.equals(SetupActivity.ROLE_PRINTER)) {
            Toast.makeText(this, "Η συσκευή δεν είναι ρυθμισμένη ως εκτυπωτής", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        tvStatus.setText("Κατάσταση: Ακρόαση παραγγελιών");

        printerManager = PrinterManager.getInstance(this);
        printerManager.loadPrintersConfig();

        if (printerManager.getPrinterByName("Ενσωματωμένος") == null) {
            printerManager.addPrinter(new BuiltinPrinter(mPrinter, "Ενσωματωμένος", "RECEIPT"));
        }

        rvPrinters.setLayoutManager(new LinearLayoutManager(this));
        printerAdapter = new PrinterAdapter(printerManager.getPrinters(), new PrinterAdapter.OnPrinterActionListener() {
            @Override
            public void onTargetChanged(PrinterDevice printer, String newTarget) {
                printer.setTarget(newTarget);
                printerManager.savePrintersConfig();
                Toast.makeText(PrinterActivity.this, "Ο εκτυπωτής " + printer.getName() + " θα εκτυπώνει: " + newTarget, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onRemovePrinter(PrinterDevice printer) {
                printerManager.removePrinter(printer);
                printerManager.savePrintersConfig();
                printerAdapter.notifyDataSetChanged();
                Toast.makeText(PrinterActivity.this, "Ο εκτυπωτής αφαιρέθηκε", Toast.LENGTH_SHORT).show();
            }
        });
        rvPrinters.setAdapter(printerAdapter);

        usbPrinterManager = new UsbPrinterManager(this);
        ensureUsbPrintersInList();
        scanAndAddUsbPrinters();

        findViewById(R.id.btnAddNetworkPrinter).setOnClickListener(v -> showAddNetworkPrinterDialog());
        findViewById(R.id.btnViewTables).setOnClickListener(v -> startActivity(new Intent(this, ActiveTablesActivity.class)));

        SharedPreferences prefsa = getSharedPreferences(PrinterSettingsActivity.PREFS_NAME, MODE_PRIVATE);
        boolean takeawayEnabled = prefsa.getBoolean(PrinterSettingsActivity.KEY_TAKEAWAY_ENABLED, false);
        boolean deliveryEnabled = prefsa.getBoolean(PrinterSettingsActivity.KEY_DELIVERY_ENABLED, false);

        Button btnTakeAway = findViewById(R.id.btnTakeAway);
        Button btnTakeAwayOrders = findViewById(R.id.btnTakeAwayOrders);
        Button btnDelivery = findViewById(R.id.btnDelivery);
        Button btnDeliveryOrders = findViewById(R.id.btnDeliveryOrders);

        btnTakeAway.setVisibility(takeawayEnabled ? View.VISIBLE : View.GONE);
        btnTakeAwayOrders.setVisibility(takeawayEnabled ? View.VISIBLE : View.GONE);
        btnDelivery.setVisibility(deliveryEnabled ? View.VISIBLE : View.GONE);
        btnDeliveryOrders.setVisibility(deliveryEnabled ? View.VISIBLE : View.GONE);

        btnTakeAway.setOnClickListener(v -> startActivity(new Intent(this, TakeAwayActivity.class)));
        btnTakeAwayOrders.setOnClickListener(v -> startActivity(new Intent(this, TakeAwayOrdersActivity.class)));
        btnDelivery.setOnClickListener(v -> startActivity(new Intent(this, DeliveryActivity.class)));
        btnDeliveryOrders.setOnClickListener(v -> startActivity(new Intent(this, DeliveryOrdersActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, PrinterSettingsActivity.class)));

        Button btnScanUsb = findViewById(R.id.btnScanUsb);
        if (btnScanUsb != null) {
            btnScanUsb.setOnClickListener(v -> scanAndAddUsbPrinters());
        }

        printExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        startFirebaseListener();
    }

    private void scanAndAddUsbPrinters() {
        ensureUsbPrintersInList();
        List<UsbDevice> devices = usbPrinterManager.findPrinters();
        if (devices.isEmpty()) {
            runOnUiThread(() -> Toast.makeText(this, "Δεν βρέθηκε κανένας USB εκτυπωτής", Toast.LENGTH_LONG).show());
            return;
        }
        for (UsbDevice dev : devices) {
            if (usbPrinterManager.hasPermission(dev)) {
                ensureUsbPrintersInList();
            } else {
                usbPrinterManager.requestPermission(dev);
            }
        }
    }

    private void showAddNetworkPrinterDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_add_network_printer, null);
        EditText etName = view.findViewById(R.id.etPrinterName);
        EditText etIp = view.findViewById(R.id.etIp);
        EditText etPort = view.findViewById(R.id.etPort);
        builder.setView(view)
                .setTitle("Προσθήκη Δικτυακού Εκτυπωτή")
                .setPositiveButton("Προσθήκη", (dialog, which) -> {
                    String name = etName.getText().toString().trim();
                    String ip = etIp.getText().toString().trim();
                    String portStr = etPort.getText().toString().trim();
                    if (name.isEmpty() || ip.isEmpty() || portStr.isEmpty()) {
                        Toast.makeText(this, "Συμπληρώστε όλα τα πεδία", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int port = Integer.parseInt(portStr);
                    printerManager.addPrinter(new NetworkPrinter(name, "RECEIPT", ip, port));
                    printerManager.savePrintersConfig();
                    printerAdapter.notifyDataSetChanged();
                    Toast.makeText(this, "Ο δικτυακός εκτυπωτής προστέθηκε", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Ακύρωση", null)
                .show();
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (device != null) ensureUsbPrintersInList();
                } else {
                    Toast.makeText(context, "Άδεια USB απορρίφθηκε", Toast.LENGTH_SHORT).show();
                }
            }
        }
    };

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            ensureUsbPrintersInList();
        }
    }

    private void ensureUsbPrintersInList() {
        if (usbPrinterManager == null) return;
        List<UsbDevice> devices = usbPrinterManager.findPrinters();
        List<PrinterManager.UsbPrinterProfile> profiles = printerManager.getPendingUsbProfiles();

        for (UsbDevice dev : devices) {
            PrinterManager.UsbPrinterProfile matchedProfile = null;
            for (PrinterManager.UsbPrinterProfile profile : profiles) {
                if (profile.vid == dev.getVendorId() && profile.pid == dev.getProductId()) {
                    matchedProfile = profile;
                    break;
                }
            }

            boolean alreadyExists = false;
            for (PrinterDevice p : printerManager.getPrinters()) {
                if (p instanceof UsbPrinter) {
                    UsbPrinter up = (UsbPrinter) p;
                    if (up.getVendorId() == dev.getVendorId() && up.getProductId() == dev.getProductId()) {
                        alreadyExists = true;
                        break;
                    }
                }
            }

            if (!alreadyExists) {
                String name;
                String target;
                boolean imageMode = false;
                if (matchedProfile != null) {
                    name = matchedProfile.name;
                    target = matchedProfile.target;
                    imageMode = matchedProfile.imageMode;
                } else {
                    name = (dev.getProductName() != null) ? dev.getProductName() : "USB " + dev.getDeviceName();
                    target = "RECEIPT";
                }
                addUsbPrinterWithNameAndTarget(dev, name, target, imageMode);
            }
        }
    }

    private void addUsbPrinterWithNameAndTarget(UsbDevice device, String name, String target, boolean imageMode) {
        if (!usbPrinterManager.hasPermission(device)) {
            runOnUiThread(() -> Toast.makeText(this, "Δεν υπάρχει άδεια για τον USB εκτυπωτή", Toast.LENGTH_SHORT).show());
            return;
        }
        boolean opened = usbPrinterManager.openPrinter(device);
        if (!opened) {
            runOnUiThread(() -> Toast.makeText(this, "Αποτυχία ανοίγματος του USB εκτυπωτή", Toast.LENGTH_SHORT).show());
            return;
        }
        UsbPrinter usbPrinter = new UsbPrinter(usbPrinterManager, device, name, target);
        usbPrinter.setImageMode(imageMode);
        printerManager.addPrinter(usbPrinter);
        printerManager.savePrintersConfig();
        if (printerAdapter != null) printerAdapter.notifyDataSetChanged();
        runOnUiThread(() -> Toast.makeText(this, "✅ USB εκτυπωτής '" + name + "' προστέθηκε", Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureUsbPrintersInList();
        if (!usbReceiverRegistered) {
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(usbReceiver, filter);
            }
            usbReceiverRegistered = true;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (usbReceiverRegistered) {
            unregisterReceiver(usbReceiver);
            usbReceiverRegistered = false;
        }
    }

    private void startFirebaseListener() {
        ordersRef = FirebaseHelper.getReference("orders");
        receiptsRef = FirebaseHelper.getReference("receipts");

        orderListener = new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot snapshot, String previousChildName) {
                Map<String, Object> order = (Map<String, Object>) snapshot.getValue();
                if (order != null) printOrder(snapshot.getKey(), order);
            }
            @Override public void onChildChanged(DataSnapshot snapshot, String previousChildName) {}
            @Override public void onChildRemoved(DataSnapshot snapshot) {}
            @Override public void onChildMoved(DataSnapshot snapshot, String previousChildName) {}
            @Override public void onCancelled(DatabaseError error) {}
        };
        ordersRef.addChildEventListener(orderListener);

        receiptListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, String previousChildName) {
                String receiptId = snapshot.getKey();
                Map<String, Object> receiptData = (Map<String, Object>) snapshot.getValue();
                if (receiptData != null) printReceipt(receiptId, receiptData);
            }
            @Override public void onChildChanged(DataSnapshot snapshot, String previousChildName) {}
            @Override public void onChildRemoved(DataSnapshot snapshot) {}
            @Override public void onChildMoved(DataSnapshot snapshot, String previousChildName) {}
            @Override public void onCancelled(DatabaseError error) {}
        };
        receiptsRef.addChildEventListener(receiptListener);
    }

    private String buildOrderText(Map<String, Object> order) {
        StringBuilder sb = new StringBuilder();
        sb.append("ΝΕΑ ΠΑΡΑΓΓΕΛΙΑ\n----------------\n");
        sb.append("Τραπέζι: ").append(order.get("tableNumber")).append("\n");
        long ts = 0;
        Object tso = order.get("timestamp");
        if (tso instanceof Long) ts = (Long) tso;
        else if (tso instanceof Double) ts = ((Double) tso).longValue();
        sb.append("Ώρα: ").append(android.text.format.DateFormat.format("dd/MM/yyyy HH:mm:ss", ts)).append("\n");
        sb.append("----------------\nΠροϊόντα:\n");
        Object itemsObj = order.get("items");
        if (itemsObj instanceof List) {
            for (Map<String, Object> item : (List<Map<String, Object>>) itemsObj) {
                sb.append("- ").append(item.get("name")).append(" x").append(item.get("quantity"));
                String comment = (String) item.get("comment");
                if (comment != null && !comment.isEmpty()) sb.append(" (").append(comment).append(")");
                sb.append("\n");
            }
        }
        sb.append("----------------\n\n\n");
        return sb.toString();
    }

    private void printOrder(String orderId, Map<String, Object> order) {
        String target = order.containsKey("printerTarget") ? order.get("printerTarget").toString() : "RECEIPT";
        String text = buildOrderText(order);

        // C1: ο έλεγχος isAvailable() (Socket) και η εκτύπωση τρέχουν σε background thread,
        // ώστε να μην ρίχνουν NetworkOnMainThreadException μέσα στο Firebase callback.
        printExecutor.execute(() -> {
            List<PrinterDevice> printers = printerManager.getPrintersByTarget(target);
            if (printers.isEmpty()) printers = printerManager.getPrintersByTarget("RECEIPT");

            boolean anyAvailable = false;
            boolean allPrinted = true;
            for (PrinterDevice printer : printers) {
                if (printer.isAvailable()) {
                    anyAvailable = true;
                    if (printer.print(text)) {
                        printer.cutPaper();
                    } else {
                        allPrinted = false;
                    }
                } else {
                    allPrinted = false;
                }
            }
            finalizeOrder(orderId, order, anyAvailable && allPrinted);
        });
    }

    private void finalizeOrder(String orderId, Map<String, Object> order, boolean printedOk) {
        String tableNumber = order.get("tableNumber") != null ? order.get("tableNumber").toString() : "0";

        // C2: Το order περνά πάντα στο active_bills (δεν χάνεται η παραγγελία), αλλά αν η εκτύπωση
        // απέτυχε το μαρκάρουμε ώστε να φαίνεται στο UI και να μπορεί να επανεκτυπωθεί.
        Map<String, Object> orderToSave = order;
        if (!printedOk) {
            orderToSave = new java.util.HashMap<>(order);
            orderToSave.put("print_failed", true);
            orderToSave.put("print_failed_at", System.currentTimeMillis());
            Log.e(TAG, "Η παραγγελία " + orderId + " δεν εκτυπώθηκε σε κανέναν διαθέσιμο εκτυπωτή");
        }

        DatabaseReference billsRef = FirebaseHelper.getReference("active_bills").child(tableNumber).child(orderId);
        billsRef.setValue(orderToSave).addOnCompleteListener(task -> {
            if (task.isSuccessful()) ordersRef.child(orderId).removeValue();
        });
    }

    private void printReceipt(String receiptId, Map<String, Object> receiptData) {
        String target = (String) receiptData.get("target");
        if (target == null) target = "RECEIPT";

        // Απόκομμα νούμερου Take Away: τυπώνεται ως εικόνα με τεράστια ψηφία
        if ("takeaway_number".equals(receiptData.get("type"))) {
            printTakeawayNumberSlip(receiptId, receiptData, target);
            return;
        }

        String fullText = buildFullReceiptText(receiptData);
        String text = buildReceiptText(receiptData);

        String mark = (String) receiptData.get("epsilon_mark");
        String uid = (String) receiptData.get("epsilon_uid");
        String auth = (String) receiptData.get("epsilon_auth");
        String qrUrl = (String) receiptData.get("epsilon_qr");

        final String finalTarget = target;
        // C1 + C2: όλα σε background thread· η απόδειξη διαγράφεται ΜΟΝΟ αν όντως εκτυπώθηκε.
        printExecutor.execute(() -> {
            List<PrinterDevice> printers = printerManager.getPrintersByTarget(finalTarget);
            if (printers.isEmpty()) printers = printerManager.getPrintersByTarget("RECEIPT");

            boolean anyAvailable = false;
            boolean allPrinted = true;
            for (PrinterDevice printer : printers) {
                if (!printer.isAvailable()) {
                    allPrinted = false;
                    continue;
                }
                anyAvailable = true;
                if (!printOneReceipt(printer, fullText, text, mark, uid, auth, qrUrl)) {
                    allPrinted = false;
                }
            }

            if (anyAvailable && allPrinted) {
                receiptsRef.child(receiptId).removeValue();
            } else {
                // Φορολογικό παραστατικό — ΔΕΝ το σβήνουμε αν δεν τυπώθηκε. Μένει για επανεκτύπωση.
                Log.e(TAG, "Η απόδειξη " + receiptId + " ΔΕΝ εκτυπώθηκε — διατηρείται στο Firebase");
                receiptsRef.child(receiptId).child("print_failed").setValue(true);
                receiptsRef.child(receiptId).child("print_failed_at").setValue(System.currentTimeMillis());
            }
        });
    }

    /** Εκτυπώνει bitmap στον σωστό τύπο εκτυπωτή. Επιστρέφει true σε επιτυχία. */
    private boolean printBitmapOn(PrinterDevice printer, Bitmap bitmap) {
        if (printer instanceof BuiltinPrinter) return ((BuiltinPrinter) printer).printBitmap(bitmap);
        if (printer instanceof NetworkPrinter) return ((NetworkPrinter) printer).printBitmap(bitmap);
        if (printer instanceof UsbPrinter) return ((UsbPrinter) printer).printBitmap(bitmap);
        return false;
    }

    /** Απόκομμα με το νούμερο παραλαβής Take Away — μεγάλα ψηφία για να φωνάζεται εύκολα. */
    private void printTakeawayNumberSlip(String receiptId, Map<String, Object> receiptData, String target) {
        String number = receiptData.get("number") != null ? String.valueOf(receiptData.get("number")) : "?";
        long ts = receiptData.get("timestamp") instanceof Number
                ? ((Number) receiptData.get("timestamp")).longValue() : System.currentTimeMillis();
        String time = android.text.format.DateFormat.format("HH:mm", ts).toString();

        printExecutor.execute(() -> {
            List<PrinterDevice> printers = printerManager.getPrintersByTarget(target);
            if (printers.isEmpty()) printers = printerManager.getPrintersByTarget("RECEIPT");

            Bitmap slip = BitmapPrinterHelper.takeawayNumberSlip("TAKE AWAY", number, "Ώρα: " + time, 576);

            boolean anyAvailable = false;
            boolean allPrinted = true;
            for (PrinterDevice printer : printers) {
                if (!printer.isAvailable()) {
                    allPrinted = false;
                    continue;
                }
                anyAvailable = true;
                boolean ok = printBitmapOn(printer, slip);
                ok = printer.print("\n\n\n") && ok;
                printer.cutPaper();
                if (!ok) allPrinted = false;
            }

            if (anyAvailable && allPrinted) {
                receiptsRef.child(receiptId).removeValue();
            } else {
                Log.e(TAG, "Το απόκομμα νούμερου " + number + " ΔΕΝ εκτυπώθηκε — διατηρείται για επανεκτύπωση");
                receiptsRef.child(receiptId).child("print_failed").setValue(true);
                receiptsRef.child(receiptId).child("print_failed_at").setValue(System.currentTimeMillis());
            }
        });
    }

    /** Εκτυπώνει μία απόδειξη σε έναν εκτυπωτή. Επιστρέφει true μόνο αν όλα τα βήματα πέτυχαν. */
    private boolean printOneReceipt(PrinterDevice printer, String fullText, String text,
                                    String mark, String uid, String auth, String qrUrl) {
        boolean ok;
        if (printer.isImageMode()) {
            Bitmap bitmap = BitmapPrinterHelper.textToBitmap(fullText, 576, 24);
            ok = printBitmapOn(printer, bitmap);
        } else {
            ok = printer.print(text);
            if (mark != null && !mark.isEmpty()) {
                // print() στα αριστερά ώστε να εκτελείται πάντα (χωρίς short-circuit).
                ok = printer.print("\n--------------------------------\n") && ok;
                ok = printer.print("ΠΑΡΟΧΟΣ: EPSILON DIGITAL\n") && ok;
                ok = printer.print("MARK: " + mark + "\n") && ok;
                ok = printer.print("UID: " + uid + "\n") && ok;
                ok = printer.print("ΚΩΔ. ΑΥΘΕΝΤΙΚΟΠΟΙΗΣΗΣ (ΥΠΑΕΣ):\n" + auth + "\n") && ok;
            }
            if (qrUrl != null && !qrUrl.isEmpty()) {
                if (printer instanceof BuiltinPrinter) {
                    Printer zcsPrinter = ((BuiltinPrinter) printer).getPrinter();
                    if (zcsPrinter != null) {
                        zcsPrinter.setPrintAppendQRCode(qrUrl, 200, 200, Layout.Alignment.ALIGN_CENTER);
                        ok = (zcsPrinter.setPrintStart() == SdkResult.SDK_OK) && ok;
                    }
                } else {
                    ok = printer.print("QR URL:\n" + qrUrl + "\n") && ok;
                }
            }
        }
        boolean tail = printer.print("\n\n\n");
        printer.cutPaper();
        return ok && tail;
    }

    private String buildFullReceiptText(Map<String, Object> receiptData) {
        StringBuilder sb = new StringBuilder(buildReceiptText(receiptData));
        String mark = (String) receiptData.get("epsilon_mark");
        String uid = (String) receiptData.get("epsilon_uid");
        String auth = (String) receiptData.get("epsilon_auth");
        String qrUrl = (String) receiptData.get("epsilon_qr");
        if (mark != null && !mark.isEmpty()) {
            sb.append("\n--------------------------------\n");
            sb.append("ΠΑΡΟΧΟΣ: EPSILON DIGITAL\n");
            sb.append("MARK: ").append(mark).append("\n");
            sb.append("UID: ").append(uid).append("\n");
            sb.append("ΚΩΔ. ΑΥΘΕΝΤΙΚΟΠΟΙΗΣΗΣ (ΥΠΑΕΣ):\n").append(auth).append("\n");
        }
        if (qrUrl != null && !qrUrl.isEmpty()) {
            sb.append("QR URL:\n").append(qrUrl).append("\n");
        }
        return sb.toString();
    }

    private String buildReceiptText(Map<String, Object> receiptData) {
        StringBuilder sb = new StringBuilder();
        String type = (String) receiptData.get("type");
        boolean isOrderSlip86 = "order_slip_86".equals(type);

        if (isOrderSlip86) sb.append("ΔΕΛΤΙΟ ΠΑΡΑΓΓΕΛΙΑΣ 8.6\n");
        else sb.append("ΑΠΟΔΕΙΞΗ ΛΙΑΝΙΚΗΣ (11.2)\n");
        sb.append("----------------\n");
        sb.append("Τραπέζι: ").append(receiptData.get("tableNumber")).append("\n");

        String timeStr;
        if (isOrderSlip86 && receiptData.containsKey("fiscal_time")) {
            timeStr = (String) receiptData.get("fiscal_time");
        } else {
            long timestamp = 0;
            Object tsObj = receiptData.get("timestamp");
            if (tsObj instanceof Long) timestamp = (Long) tsObj;
            else if (tsObj instanceof Integer) timestamp = ((Integer) tsObj).longValue();
            else if (tsObj instanceof String) { try { timestamp = Long.parseLong((String) tsObj); } catch (NumberFormatException ignored) {} }
            if (timestamp == 0) timestamp = System.currentTimeMillis();
            timeStr = android.text.format.DateFormat.format("HH:mm:ss", timestamp).toString();
        }
        sb.append("Ώρα: ").append(timeStr).append("\n");
        sb.append("----------------\nΠροϊόντα:\n");

        Object itemsObj = receiptData.get("items");
        if (itemsObj instanceof List) {
            for (Map<String, Object> item : (List<Map<String, Object>>) itemsObj) {
                String name = (String) item.get("name");
                int qty = ((Number) item.get("quantity")).intValue();
                double price = ((Number) item.get("price")).doubleValue();
                double vatPercent = item.containsKey("vatPercent") ? ((Number) item.get("vatPercent")).doubleValue() : 13.0;
                double lineTotal = price * qty;
                sb.append(String.format("%s x%d  %.2f€ (ΦΠΑ %.0f%%)\n", name, qty, lineTotal, vatPercent));
                String comment = (String) item.get("comment");
                if (comment != null && !comment.isEmpty()) sb.append("   Σχόλιο: ").append(comment).append("\n");
            }
        }

        if (receiptData.containsKey("totalAmount")) {
            double total = 0.0;
            Object amountObj = receiptData.get("totalAmount");
            if (amountObj instanceof Double) total = (Double) amountObj;
            else if (amountObj instanceof Long) total = ((Long) amountObj).doubleValue();
            else if (amountObj instanceof Integer) total = ((Integer) amountObj).doubleValue();
            else if (amountObj instanceof String) { try { total = Double.parseDouble((String) amountObj); } catch (NumberFormatException ignored) {} }
            sb.append("----------------\nΣΥΝΟΛΟ: €").append(String.format("%.2f", total)).append("\n");
        }
        sb.append("----------------\n");
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (statusManager != null) statusManager.stopMonitoring();
        if (orderListener != null) ordersRef.removeEventListener(orderListener);
        if (receiptListener != null) receiptsRef.removeEventListener(receiptListener);
        if (printExecutor != null) printExecutor.shutdownNow();
        if (usbPrinterManager != null) usbPrinterManager.close();
        mSys.sysPowerOff();
    }
}