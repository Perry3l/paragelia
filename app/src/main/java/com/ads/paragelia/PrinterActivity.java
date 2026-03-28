package com.ads.paragelia;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Layout;
import android.util.Log;
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

    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_DEVICE_ROLE = "device_role";
    private static final String TAG = "PrinterActivity";

    private TextView tvStatus;
    private Button btnSelectPrinter;
    private Button btnViewTables;
    private DatabaseReference ordersRef;
    private ChildEventListener orderListener;

    private DriverManager mDriverManager;
    private Sys mSys;
    private Printer mPrinter;
    private DatabaseReference receiptsRef;
    private ChildEventListener receiptListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_printer);

        tvStatus = findViewById(R.id.tvStatus);
        btnSelectPrinter = findViewById(R.id.btnSelectPrinter);
        btnViewTables = findViewById(R.id.btnViewTables);

        // Αρχικοποίηση ZCS SDK
        mDriverManager = DriverManager.getInstance();
        mSys = mDriverManager.getBaseSysDevice();
        mPrinter = mDriverManager.getPrinter();

        // Ενεργοποίηση του εκτυπωτή (για ZCS 108)
        int initRet = mSys.sdkInit();
        if (initRet != SdkResult.SDK_OK) {
            mSys.sysPowerOn();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            initRet = mSys.sdkInit();
            if (initRet != SdkResult.SDK_OK) {
                Toast.makeText(this, "Αποτυχία αρχικοποίησης SDK", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }

        // Έλεγχος ρόλου
        SharedPreferences prefs = getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE);
        String role = prefs.getString(SetupActivity.KEY_DEVICE_ROLE, null);
        if (role == null || !role.equals(SetupActivity.ROLE_PRINTER)) {
            Toast.makeText(this, "Η συσκευή δεν είναι ρυθμισμένη ως εκτυπωτής", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        tvStatus.setText("Κατάσταση: Ακρόαση παραγγελιών");

        // Κουμπί επιλογής εκτυπωτή (αν χρειαστεί, αλλά το ZCS SDK το διαχειρίζεται εσωτερικά)
        btnSelectPrinter.setOnClickListener(v -> {
            Toast.makeText(this, "Ο εκτυπωτής είναι ενσωματωμένος", Toast.LENGTH_SHORT).show();
        });

        btnViewTables.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(PrinterActivity.this, ActiveTablesActivity.class);
            startActivity(intent);
        });

        // Έναρξη ακρόασης Firebase
        startFirebaseListener();
    }

    private void startFirebaseListener() {
        ordersRef = FirebaseDatabase.getInstance().getReference("orders");
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
        receiptsRef = FirebaseDatabase.getInstance().getReference("receipts");
        receiptListener = new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot snapshot, String previousChildName) {
                // Στις αποδείξεις, το κλειδί (key) είναι το Νούμερο του Τραπεζιού (π.χ. "5")
                String tableNumber = snapshot.getKey();
                // Το value είναι ΟΛΕΣ οι παραγγελίες που είχαν χτυπηθεί σε αυτό το τραπέζι
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

        // Επικεφαλίδα
        format.setAli(Layout.Alignment.ALIGN_CENTER);
        mPrinter.setPrintAppendString("ΤΕΛΙΚΟΣ ΛΟΓΑΡΙΑΣΜΟΣ", format);
        mPrinter.setPrintAppendString("----------------", format);

        // Πληροφορίες Τραπεζιού & Ώρας
        format.setAli(Layout.Alignment.ALIGN_NORMAL);
        mPrinter.setPrintAppendString("Τραπέζι: " + tableNumber, format);
        mPrinter.setPrintAppendString("Ημερομηνία: " + android.text.format.DateFormat.format("dd/MM/yyyy HH:mm:ss", System.currentTimeMillis()), format);

        format.setAli(Layout.Alignment.ALIGN_CENTER);
        mPrinter.setPrintAppendString("----------------", format);

        format.setAli(Layout.Alignment.ALIGN_NORMAL);
        mPrinter.setPrintAppendString("Προϊόντα:", format);

// Διαβάζουμε όλες τις παραγγελίες του τραπεζιού
        for (Object orderObj : tableOrders.values()) {
            if (orderObj instanceof Map) {
                Map<String, Object> order = (Map<String, Object>) orderObj;
                Object itemsObj = order.get("items");

                if (itemsObj instanceof List) {
                    List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
                    for (Map<String, Object> item : items) {
                        String name = (String) item.get("name");
                        Object qtyObj = item.get("quantity");
                        int quantity = (qtyObj instanceof Long) ? ((Long) qtyObj).intValue() : (int) qtyObj;

                        mPrinter.setPrintAppendString(name + "  x" + quantity, format);
                    }
                }
            }
        }

        // --- ΝΕΟ: ΕΚΤΥΠΩΣΗ ΣΤΟΙΧΕΙΩΝ EPSILON DIGITAL ---
        String mark = tableOrders.containsKey("epsilon_mark") ? (String) tableOrders.get("epsilon_mark") : null;
        String uid = tableOrders.containsKey("epsilon_uid") ? (String) tableOrders.get("epsilon_uid") : null;
        String authCode = tableOrders.containsKey("epsilon_auth") ? (String) tableOrders.get("epsilon_auth") : null;
        String qrUrl = tableOrders.containsKey("epsilon_qr") ? (String) tableOrders.get("epsilon_qr") : null;

        if (mark != null) {
            format.setAli(Layout.Alignment.ALIGN_CENTER);
            mPrinter.setPrintAppendString("----------------", format);
            format.setAli(Layout.Alignment.ALIGN_NORMAL);
            format.setTextSize(20); // Πιο μικρά γράμματα για τα στοιχεία Εφορίας
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
            format.setTextSize(25); // Επαναφορά μεγέθους
        }

        format.setAli(Layout.Alignment.ALIGN_CENTER);
        mPrinter.setPrintAppendString("----------------", format);
        mPrinter.setPrintAppendString("Σας ευχαριστούμε πολύ!", format);
        mPrinter.setPrintAppendString("\n\n\n", format);

        // Εκτύπωση
        int ret = mPrinter.setPrintStart();
// (Συνεχίζει κανονικά όπως το είχες, με το SdkResult.SDK_OK)
        if (ret == SdkResult.SDK_OK) {
            runOnUiThread(() -> {
                Toast.makeText(PrinterActivity.this, "Η απόδειξη εκτυπώθηκε", Toast.LENGTH_SHORT).show();
                tvStatus.setText("Κατάσταση: Ακρόαση παραγγελιών");
            });

            // Διαγραφή της απόδειξης από το Firebase εφόσον τυπώθηκε επιτυχώς
            receiptsRef.child(tableNumber).removeValue();

            // Κόψιμο χαρτιού
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

        // Έλεγχος χαρτιού
        int printStatus = mPrinter.getPrinterStatus();
        if (printStatus == SdkResult.SDK_PRN_STATUS_PAPEROUT) {
            runOnUiThread(() -> {
                Toast.makeText(PrinterActivity.this, "Δεν υπάρχει χαρτί", Toast.LENGTH_LONG).show();
                tvStatus.setText("Κατάσταση: Δεν υπάρχει χαρτί");
            });
            return;
        }

        // Δημιουργία μορφοποίησης κειμένου
        PrnStrFormat format = new PrnStrFormat();
        format.setTextSize(25);
        format.setStyle(PrnTextStyle.NORMAL);
        format.setFont(PrnTextFont.MONOSPACE); // Μονοδιάστατη γραμματοσειρά για ευθυγράμμιση

        // Εκτύπωση επικεφαλίδας
        format.setAli(Layout.Alignment.ALIGN_CENTER);
        mPrinter.setPrintAppendString("ΝΕΑ ΠΑΡΑΓΓΕΛΙΑ", format);
        mPrinter.setPrintAppendString("----------------", format);

        // Στοιχεία παραγγελίας
        format.setAli(Layout.Alignment.ALIGN_NORMAL);
        mPrinter.setPrintAppendString("Τραπέζι: " + order.get("tableNumber"), format);
        // Ασφαλής ανάγνωση του timestamp ως Long
        long timestamp = 0;
        Object timestampObj = order.get("timestamp");
        if (timestampObj instanceof Long) {
            timestamp = (Long) timestampObj;
        } else if (timestampObj instanceof Double) {
            // Σε περίπτωση που το Firebase το επιστρέψει ως Double
            timestamp = ((Double) timestampObj).longValue();
        }

        // Διαμόρφωση και εκτύπωση της ώρας απευθείας από τον αριθμό
        mPrinter.setPrintAppendString("Ώρα: " + android.text.format.DateFormat.format("dd/MM/yyyy HH:mm:ss", timestamp), format);

        format.setAli(Layout.Alignment.ALIGN_CENTER);
        mPrinter.setPrintAppendString("----------------", format);

        format.setAli(Layout.Alignment.ALIGN_NORMAL);
        mPrinter.setPrintAppendString("Προϊόντα:", format);

        // Προϊόντα
        Object itemsObj = order.get("items");
        if (itemsObj instanceof List) {
            List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
            for (Map<String, Object> item : items) {
                String name = (String) item.get("name");
                Object qtyObj = item.get("quantity");
                int quantity = (qtyObj instanceof Long) ? ((Long) qtyObj).intValue() : (int) qtyObj;
                mPrinter.setPrintAppendString(name + "  x" + quantity, format);
            }
        }

        format.setAli(Layout.Alignment.ALIGN_CENTER);
        mPrinter.setPrintAppendString("----------------", format);
        mPrinter.setPrintAppendString("\n\n\n", format);
        mPrinter.setPrintAppendString("\n\n\n", format);

        // Εκτύπωση
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (orderListener != null) {
            ordersRef.removeEventListener(orderListener);
        }
        if (receiptListener != null) {
            receiptsRef.removeEventListener(receiptListener); // Προσθήκη για τις αποδείξεις
        }
        mSys.sysPowerOff();
    }
}