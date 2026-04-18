package com.ads.paragelia;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Layout;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.zcs.sdk.DriverManager;
import com.zcs.sdk.Printer;
import com.zcs.sdk.SdkResult;
import com.zcs.sdk.Sys;
import com.zcs.sdk.print.PrnStrFormat;
import com.zcs.sdk.print.PrnTextFont;
import com.zcs.sdk.print.PrnTextStyle;

import java.util.List;
import java.util.Map;

public class PrinterActivity extends AppCompatActivity {

    private static final String TAG = "PrinterActivity";
    private TextView tvStatus;
    private Button btnSelectPrinter;
    private Button btnViewTables;
    private DatabaseReference ordersRef;
    private DatabaseReference receiptsRef;
    private ChildEventListener orderListener;
    private ChildEventListener receiptListener;
    private Button btnSettings;
    private PrinterStatusManager statusManager;
    private DriverManager mDriverManager;
    private Sys mSys;
    private Button btnTakeAwayOrders;
    private Printer mPrinter;
    private Button btnTakeAway;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_printer);

        tvStatus = findViewById(R.id.tvStatus);
        btnSelectPrinter = findViewById(R.id.btnSelectPrinter);
        btnViewTables = findViewById(R.id.btnViewTables);

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

        btnTakeAway = findViewById(R.id.btnTakeAway);

// Έλεγχος αν είναι ενεργοποιημένο το Take Away από τις ρυθμίσεις εκτυπωτή
        SharedPreferences prefsa = getSharedPreferences(PrinterSettingsActivity.PREFS_NAME, MODE_PRIVATE);
        boolean takeawayEnabled = prefsa.getBoolean(PrinterSettingsActivity.KEY_TAKEAWAY_ENABLED, false);

        if (takeawayEnabled) {
            btnTakeAway.setVisibility(View.VISIBLE);
            btnTakeAway.setOnClickListener(v -> {
                Intent intent = new Intent(PrinterActivity.this, TakeAwayActivity.class);
                startActivity(intent);
            });
        } else {
            btnTakeAway.setVisibility(View.GONE);
        }

        SharedPreferences prefs = getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE);
        String role = prefs.getString(SetupActivity.KEY_DEVICE_ROLE, null);
        if (role == null || !role.equals(SetupActivity.ROLE_PRINTER)) {
            Toast.makeText(this, "Η συσκευή δεν είναι ρυθμισμένη ως εκτυπωτής", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        tvStatus.setText("Κατάσταση: Ακρόαση παραγγελιών");

        btnSelectPrinter.setOnClickListener(v -> Toast.makeText(this, "Ο εκτυπωτής είναι ενσωματωμένος", Toast.LENGTH_SHORT).show());
        btnViewTables.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(PrinterActivity.this, ActiveTablesActivity.class);
            startActivity(intent);
        });
        btnTakeAwayOrders = findViewById(R.id.btnTakeAwayOrders);
        btnTakeAwayOrders.setOnClickListener(v -> {
            startActivity(new Intent(PrinterActivity.this, TakeAwayOrdersActivity.class));
        });
        btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(PrinterActivity.this, PrinterSettingsActivity.class);
            startActivity(intent);
        });

        startFirebaseListener();
    }

    private void startFirebaseListener() {
        ordersRef = FirebaseDatabase.getInstance().getReference("orders");
        receiptsRef = FirebaseDatabase.getInstance().getReference("receipts");

        orderListener = new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot snapshot, String previousChildName) {
                Map<String, Object> order = (Map<String, Object>) snapshot.getValue();
                if (order != null) {
                    printOrder(snapshot.getKey(), order);
                }
            }
            @Override public void onChildChanged(DataSnapshot snapshot, String previousChildName) {}
            @Override public void onChildRemoved(DataSnapshot snapshot) {}
            @Override public void onChildMoved(DataSnapshot snapshot, String previousChildName) {}
            @Override public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Firebase error: " + error.getMessage());
            }
        };
        ordersRef.addChildEventListener(orderListener);

        receiptListener = new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot snapshot, String previousChildName) {
                String tableNumber = snapshot.getKey();
                Map<String, Object> tableOrders = (Map<String, Object>) snapshot.getValue();
                if (tableOrders != null) {
                    printReceipt(tableNumber, tableOrders);
                }
            }
            @Override public void onChildChanged(DataSnapshot snapshot, String previousChildName) {}
            @Override public void onChildRemoved(DataSnapshot snapshot) {}
            @Override public void onChildMoved(DataSnapshot snapshot, String previousChildName) {}
            @Override public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Σφάλμα Firebase (αποδείξεις): " + error.getMessage());
            }
        };
        receiptsRef.addChildEventListener(receiptListener);
    }

    private void printReceipt(String tableNumber, Map<String, Object> tableOrders) {
        runOnUiThread(() -> {
            tvStatus.setText("Κατάσταση: Εκτύπωση Απόδειξης...");
            Toast.makeText(PrinterActivity.this, "Λήφθηκε απόδειξη για το Τραπέζι " + tableNumber, Toast.LENGTH_SHORT).show();
        });

        int printStatus = mPrinter.getPrinterStatus();
        if (printStatus == SdkResult.SDK_PRN_STATUS_PAPEROUT) {
            runOnUiThread(() -> Toast.makeText(PrinterActivity.this, "Δεν υπάρχει χαρτί", Toast.LENGTH_LONG).show());
            return;
        }

        PrnStrFormat format = new PrnStrFormat();
        format.setTextSize(25);
        format.setStyle(PrnTextStyle.NORMAL);
        format.setFont(PrnTextFont.MONOSPACE);

        format.setAli(Layout.Alignment.ALIGN_CENTER);
        mPrinter.setPrintAppendString("ΤΕΛΙΚΟΣ ΛΟΓΑΡΙΑΣΜΟΣ", format);
        mPrinter.setPrintAppendString("----------------", format);

        format.setAli(Layout.Alignment.ALIGN_NORMAL);
        mPrinter.setPrintAppendString("Τραπέζι: " + tableNumber, format);
        mPrinter.setPrintAppendString("Ημερομηνία: " + android.text.format.DateFormat.format("dd/MM/yyyy HH:mm:ss", System.currentTimeMillis()), format);

        format.setAli(Layout.Alignment.ALIGN_CENTER);
        mPrinter.setPrintAppendString("----------------", format);

        format.setAli(Layout.Alignment.ALIGN_NORMAL);
        mPrinter.setPrintAppendString("Προϊόντα:", format);

        // Διαβάζουμε τα items ανεξαρτήτως δομής
        if (tableOrders.containsKey("items")) {
            // Απευθείας items (π.χ. από προσωρινή απόδειξη)
            Object itemsObj = tableOrders.get("items");
            if (itemsObj instanceof List) {
                printItems((List<Map<String, Object>>) itemsObj, format);
            }
        } else {
            // Παλιά δομή: πολλαπλές παραγγελίες κάτω από το τραπέζι
            for (Object orderObj : tableOrders.values()) {
                if (orderObj instanceof Map) {
                    Map<String, Object> order = (Map<String, Object>) orderObj;
                    Object itemsObj = order.get("items");
                    if (itemsObj instanceof List) {
                        printItems((List<Map<String, Object>>) itemsObj, format);
                    }
                }
            }
        }

        // Στοιχεία Epsilon
        if (tableOrders.containsKey("epsilon_mark")) {
            String mark = (String) tableOrders.get("epsilon_mark");
            String uid = (String) tableOrders.get("epsilon_uid");
            String authCode = (String) tableOrders.get("epsilon_auth");
            String qrUrl = (String) tableOrders.get("epsilon_qr");

            format.setAli(Layout.Alignment.ALIGN_CENTER);
            mPrinter.setPrintAppendString("----------------", format);
            format.setAli(Layout.Alignment.ALIGN_NORMAL);
            format.setTextSize(20);
            mPrinter.setPrintAppendString("ΠΑΡΟΧΟΣ: EPSILON DIGITAL", format);
            mPrinter.setPrintAppendString("ΜΑΡΚ: " + mark, format);
            mPrinter.setPrintAppendString("UID: " + uid, format);
            mPrinter.setPrintAppendString("ΚΩΔ. ΑΥΘΕΝΤΙΚΟΠΟΙΗΣΗΣ:", format);
            mPrinter.setPrintAppendString(authCode, format);

            if (qrUrl != null && !qrUrl.isEmpty()) {
                mPrinter.setPrintAppendString("----------------", format);
                try {
                    mPrinter.setPrintAppendQRCode(qrUrl, 200, 200, Layout.Alignment.ALIGN_CENTER);
                } catch (Exception e) {
                    mPrinter.setPrintAppendString("QR URL: " + qrUrl, format);
                }
            }
            format.setTextSize(25);
        }

        format.setAli(Layout.Alignment.ALIGN_CENTER);
        mPrinter.setPrintAppendString("----------------", format);
        mPrinter.setPrintAppendString("Σας ευχαριστούμε πολύ!", format);
        mPrinter.setPrintAppendString("\n\n\n", format);

        int ret = mPrinter.setPrintStart();
        if (ret == SdkResult.SDK_OK) {
            runOnUiThread(() -> {
                Toast.makeText(PrinterActivity.this, "Η απόδειξη εκτυπώθηκε", Toast.LENGTH_SHORT).show();
                tvStatus.setText("Κατάσταση: Ακρόαση παραγγελιών");
            });
            receiptsRef.child(tableNumber).removeValue();
            if (mPrinter.isSuppoerCutter()) {
                mPrinter.openPrnCutter((byte) 1);
            }
        } else {
            runOnUiThread(() -> tvStatus.setText("Κατάσταση: Σφάλμα εκτύπωσης (" + ret + ")"));
        }
    }

    private void printOrder(String orderId, Map<String, Object> order) {
        runOnUiThread(() -> {
            tvStatus.setText("Κατάσταση: Εκτύπωση...");
            Toast.makeText(PrinterActivity.this, "Λήφθηκε παραγγελία, εκτύπωση...", Toast.LENGTH_SHORT).show();
        });

        int printStatus = mPrinter.getPrinterStatus();
        if (printStatus == SdkResult.SDK_PRN_STATUS_PAPEROUT) {
            runOnUiThread(() -> {
                Toast.makeText(PrinterActivity.this, "Δεν υπάρχει χαρτί", Toast.LENGTH_LONG).show();
                tvStatus.setText("Κατάσταση: Δεν υπάρχει χαρτί");
            });
            return;
        }

        PrnStrFormat format = new PrnStrFormat();
        format.setTextSize(25);
        format.setStyle(PrnTextStyle.NORMAL);
        format.setFont(PrnTextFont.MONOSPACE);

        format.setAli(Layout.Alignment.ALIGN_CENTER);
        mPrinter.setPrintAppendString("ΝΕΑ ΠΑΡΑΓΓΕΛΙΑ", format);
        mPrinter.setPrintAppendString("----------------", format);

        format.setAli(Layout.Alignment.ALIGN_NORMAL);
        mPrinter.setPrintAppendString("Τραπέζι: " + order.get("tableNumber"), format);

        long timestamp = 0;
        Object timestampObj = order.get("timestamp");
        if (timestampObj instanceof Long) {
            timestamp = (Long) timestampObj;
        } else if (timestampObj instanceof Double) {
            timestamp = ((Double) timestampObj).longValue();
        }
        mPrinter.setPrintAppendString("Ώρα: " + android.text.format.DateFormat.format("dd/MM/yyyy HH:mm:ss", timestamp), format);

        format.setAli(Layout.Alignment.ALIGN_CENTER);
        mPrinter.setPrintAppendString("----------------", format);

        format.setAli(Layout.Alignment.ALIGN_NORMAL);
        mPrinter.setPrintAppendString("Προϊόντα:", format);

        Object itemsObj = order.get("items");
        if (itemsObj instanceof List) {
            printItems((List<Map<String, Object>>) itemsObj, format);
        }

        format.setAli(Layout.Alignment.ALIGN_CENTER);
        mPrinter.setPrintAppendString("----------------", format);
        mPrinter.setPrintAppendString("\n\n\n", format);
        mPrinter.setPrintAppendString("\n\n\n", format);

        int ret = mPrinter.setPrintStart();
        if (ret == SdkResult.SDK_OK) {
            runOnUiThread(() -> {
                Toast.makeText(PrinterActivity.this, "Εκτύπωση επιτυχής", Toast.LENGTH_SHORT).show();
                tvStatus.setText("Κατάσταση: Ακρόαση παραγγελιών");
            });

            String tableNumber = String.valueOf(order.get("tableNumber"));
            DatabaseReference billsRef = FirebaseDatabase.getInstance()
                    .getReference("active_bills")
                    .child(tableNumber)
                    .child(orderId);

            billsRef.setValue(order)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "Η παραγγελία μεταφέρθηκε στο τραπέζι: " + tableNumber);
                        ordersRef.child(orderId).removeValue()
                                .addOnSuccessListener(aVoid2 -> Log.d(TAG, "Η παραγγελία διαγράφηκε από την ουρά εκτύπωσης: " + orderId))
                                .addOnFailureListener(e -> Log.e(TAG, "Αποτυχία διαγραφής από την ουρά", e));
                    })
                    .addOnFailureListener(e -> Log.e(TAG, "Αποτυχία μεταφοράς της παραγγελίας στους λογαριασμούς", e));

            if (mPrinter.isSuppoerCutter()) {
                mPrinter.openPrnCutter((byte) 1);
            }
        } else {
            runOnUiThread(() -> {
                Toast.makeText(PrinterActivity.this, "Σφάλμα εκτύπωσης: " + ret, Toast.LENGTH_LONG).show();
                tvStatus.setText("Κατάσταση: Σφάλμα εκτύπωσης");
            });
        }

    }

    // Βοηθητική μέθοδος για εκτύπωση λίστας ειδών
    private void printItems(List<Map<String, Object>> items, PrnStrFormat format) {
        for (Map<String, Object> item : items) {
            String name = (String) item.get("name");
            Object qtyObj = item.get("quantity");
            int quantity = (qtyObj instanceof Number) ? ((Number) qtyObj).intValue() : 1;
            String comment = (String) item.get("comment");
            String line = name + "  x" + quantity;
            if (comment != null && !comment.isEmpty()) {
                line += " (" + comment + ")";
            }
            mPrinter.setPrintAppendString(line, format);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (statusManager != null) {
            statusManager.stopMonitoring();
        }
        if (orderListener != null) {
            ordersRef.removeEventListener(orderListener);
        }
        if (receiptListener != null) {
            receiptsRef.removeEventListener(receiptListener);
        }
        mSys.sysPowerOff();
    }
    @Override
    protected void onResume() {
        super.onResume();
        updateTakeAwayButton();
    }

    private void updateTakeAwayButton() {
        SharedPreferences prefs = getSharedPreferences(PrinterSettingsActivity.PREFS_NAME, MODE_PRIVATE);
        boolean takeawayEnabled = prefs.getBoolean(PrinterSettingsActivity.KEY_TAKEAWAY_ENABLED, false);

        if (takeawayEnabled) {
            btnTakeAway.setVisibility(View.VISIBLE);
            btnTakeAway.setOnClickListener(v -> {
                Intent intent = new Intent(PrinterActivity.this, TakeAwayActivity.class);
                startActivity(intent);
            });
        } else {
            btnTakeAway.setVisibility(View.GONE);
        }
    }
}