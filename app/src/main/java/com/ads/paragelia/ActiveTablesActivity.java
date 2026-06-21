package com.ads.paragelia;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ads.paragelia.paroxos.EpsilonIntegrationHelper;
import com.ads.paragelia.paroxos.SendResponse;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ActiveTablesActivity extends BaseActivity {
    private boolean isPartialPayment = false;
    private boolean isMoveMode = false;
    public String moveSourceTable = null;
    private CardView moveSourceCard = null;
    private static final String TAG = "ActiveTables";
    private RecyclerView tablesRecyclerView;
    private ActiveTableAdapter adapter;
    private DatabaseReference billsRef;
    private static final int SMS_PERMISSION_CODE = 101;
    private static final int REQUEST_SPLIT_ITEMS = 2002;
    private static final int REQUEST_SPLIT_ITEMS_PARTIAL = 2003;
    private boolean showAllTablesForMove = false;
    private Button btnShowEmpty;
    private boolean showOnlyEmpty = false;

    private String pendingPhone = "";
    private String pendingReceiptText = "";
    private PaymentCompleteCallback pendingPaymentCallback;

    private double pendingAmount = 0.0;
    private String pendingOrderDetails = "";
    private String pendingTableNumber = "";
    private Map<String, Object> pendingTableData;
    private int pendingPaymentType = 7;
    private boolean isMergeMode = false;
    public String sourceTable = null;
    private CardView selectedSourceCard = null;
    private Button btnMergeTables;
    private double pendingSecondAmount = 0.0;
    private String pendingSecondOrderDetails = "";
    private List<Double> splitAmounts = new ArrayList<>();
    private int currentSplitIndex = 0;

    private SystemSettingsManager settingsManager;

    private Runnable settingsListener;


    private EditText etSearchTable;
    private String currentSearchQuery = "";
    private List<List<Map<String, Object>>> pendingSplitPartsItems;
    private List<Map<String, Object>> pendingPaymentItems;
    private ValueEventListener activeTablesListener;
    private Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;
    private PaymentManager paymentManager;
    private TextView tvEmpty;
    private ProgressBar progressBar;
    private Button btnUnmergeTables;
    private boolean isUnmergeMode = false;
    private boolean orderOnlyMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_active_tables);
        showMemoryOverlay();

        settingsManager = SystemSettingsManager.getInstance();
        settingsListener = () -> loadActiveTables();
        settingsManager.addListener(settingsListener);

        tablesRecyclerView = findViewById(R.id.tablesRecyclerView);
        billsRef = FirebaseHelper.getReference("active_bills");
        paymentManager = new PaymentManager(this);

        tvEmpty = findViewById(R.id.tvEmpty);
        progressBar = findViewById(R.id.progressBar);
        tablesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
// Σωστή σειρά:
        adapter = new ActiveTableAdapter();
        SharedPreferences orderPrefs = getSharedPreferences(SettingsActivity.PREFS_ORDER_MODE, MODE_PRIVATE);
        boolean orderOnlyMode = orderPrefs.getBoolean(SettingsActivity.KEY_ORDER_ONLY_MODE, false);
        adapter.setOrderOnlyMode(orderOnlyMode);

        btnUnmergeTables = findViewById(R.id.btnUnmergeTables);
        btnUnmergeTables.setOnClickListener(v -> {
            if (isMergeMode) {
                Toast.makeText(this, "Βγείτε πρώτα από τη συγχώνευση", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isUnmergeMode) {
                isUnmergeMode = true;
                btnUnmergeTables.setText("Ακύρωση Διάσπασης");
                btnMergeTables.setVisibility(View.GONE);
                adapter.notifyDataSetChanged();
                Toast.makeText(this, "Επιλέξτε το τραπέζι που θέλετε να διασπάσετε", Toast.LENGTH_SHORT).show();
            } else {
                isUnmergeMode = false;
                btnUnmergeTables.setText("Διάσπαση");
                btnMergeTables.setVisibility(View.VISIBLE);
                adapter.notifyDataSetChanged();
            }
        });

        adapter.setOnTableInteractionListener(new ActiveTableAdapter.OnTableInteractionListener() {
            @Override
            public void onTableClicked(ActiveTableAdapter.TableCardData data) {
                if (isUnmergeMode) {
                    if (!data.isEmpty && data.tableData != null && data.tableData.containsKey("current_order")) {
                        Map<String, Object> cur = (Map<String, Object>) data.tableData.get("current_order");
                        if (cur != null && cur.containsKey("merged_from")) {
                            unmergeTable(data.tableNumber);
                            return;
                        }
                    }
                    Toast.makeText(ActiveTablesActivity.this, "Το τραπέζι δεν είναι συγχωνευμένο", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (data.isEmpty && !isMoveMode && !isMergeMode) {
                    Intent intent = new Intent(ActiveTablesActivity.this, NewOrderActivity.class);
                    intent.putExtra("EXTRA_TABLE_NUMBER", data.tableNumber);
                    startActivity(intent);
                    return;
                }

                if (isMoveMode) {
                    if (moveSourceTable == null) {
                        if (data.isEmpty) {
                            Toast.makeText(ActiveTablesActivity.this, "Δεν μπορείτε να μετακινήσετε από κενό τραπέζι", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        moveSourceTable = data.tableNumber;
                        adapter.notifyDataSetChanged();
                        Toast.makeText(ActiveTablesActivity.this, "Τραπέζι " + data.tableNumber + " επιλέχθηκε ως πηγή. Επιλέξτε προορισμό.", Toast.LENGTH_SHORT).show();
                    } else {
                        if (moveSourceTable.equals(data.tableNumber)) {
                            Toast.makeText(ActiveTablesActivity.this, "Δεν μπορείτε να μετακινήσετε στο ίδιο τραπέζι", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        performMoveTable(moveSourceTable, data.tableNumber);
                    }
                } else if (isMergeMode) {
                    if (sourceTable == null) {
                        if (data.isEmpty) {
                            Toast.makeText(ActiveTablesActivity.this, "Δεν μπορείτε να επιλέξετε κενό τραπέζι ως πηγή", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        sourceTable = data.tableNumber;
                        adapter.notifyDataSetChanged();
                        Toast.makeText(ActiveTablesActivity.this, "Τραπέζι " + data.tableNumber + " επιλέχθηκε ως πηγή. Επιλέξτε προορισμό.", Toast.LENGTH_SHORT).show();
                    } else {
                        if (sourceTable.equals(data.tableNumber)) {
                            Toast.makeText(ActiveTablesActivity.this, "Δεν μπορείτε να συγχωνεύσετε το ίδιο τραπέζι", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        performMerge(sourceTable, data.tableNumber);
                    }
                } else {
                    if ("ordered".equals(data.status)) {
                        String[] orangeOptions;
                        if (orderOnlyMode) {
                            orangeOptions = new String[]{
                                    "❌ Ακύρωση Παραγγελίας",
                                    "🛒 Προσθήκη/Επεξεργασία Ειδών"
                            };
                        } else {
                            orangeOptions = new String[]{
                                    "📝 Έκδοση 8.6 (Σήμανση στην ΑΑΔΕ)",
                                    "❌ Ακύρωση Παραγγελίας",
                                    "🛒 Προσθήκη/Επεξεργασία Ειδών"
                            };
                        }
                        new AlertDialog.Builder(ActiveTablesActivity.this)
                                .setTitle("Επιλογές Τραπεζιού " + data.tableNumber)
                                .setItems(orangeOptions, (dialog, which) -> {
                                    if (!orderOnlyMode && which == 0) {
                                        issue86ForOrangeTable(data);
                                    } else if (which == 0 && orderOnlyMode) {
                                        onCancelClicked(data);
                                    } else if (which == 1 && !orderOnlyMode) {
                                        onCancelClicked(data);
                                    } else if (which == (orderOnlyMode ? 1 : 2)) {
                                        Intent intent = new Intent(ActiveTablesActivity.this, NewOrderActivity.class);
                                        intent.putExtra("EXTRA_TABLE_NUMBER", data.tableNumber);
                                        startActivity(intent);
                                    }
                                })
                                .show();
                    } else {
                        Intent intent = new Intent(ActiveTablesActivity.this, NewOrderActivity.class);
                        intent.putExtra("EXTRA_TABLE_NUMBER", data.tableNumber);
                        startActivity(intent);
                    }
                }
            }

            private void issue86ForOrangeTable(ActiveTableAdapter.TableCardData data) {
                // Use original items with VAT for printing
                List<Map<String, Object>> originalItems = extractAllItemsWithVat(data.tableData);
                // For Epsilon API, we can use the same list (VAT doesn't matter for order slip)
                List<Map<String, Object>> itemsForEpsilon = extractAllItems(data.tableData);

                if (itemsForEpsilon.isEmpty()) {
                    Toast.makeText(ActiveTablesActivity.this, "Δεν βρέθηκαν είδη για αποστολή", Toast.LENGTH_SHORT).show();
                    return;
                }

                Toast.makeText(ActiveTablesActivity.this, "Διαβίβαση 8.6 στην Epsilon Digital...", Toast.LENGTH_SHORT).show();
                EpsilonIntegrationHelper.sendOrderSlip86(ActiveTablesActivity.this, data.tableNumber, itemsForEpsilon, false,
                        new EpsilonIntegrationHelper.CallbackWithResult<SendResponse>() {
                            @Override
                            public void onSuccess(SendResponse result) {
                                long mark = result.getMark();
                                String uid = result.getUid() != null ? result.getUid() : "";
                                String qr = result.getQrCode() != null ? result.getQrCode() : "";
                                String auth = result.getAuthenticationCode() != null ? result.getAuthenticationCode() : "";

                                // Δημιουργία της ώρας μία φορά
                                String fiscalTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

                                DatabaseReference tableRef = billsRef.child(data.tableNumber);
                                Map<String, Object> lastFiscal = new HashMap<>();
                                lastFiscal.put("mark", String.valueOf(mark));
                                lastFiscal.put("uid", uid);
                                lastFiscal.put("qr", qr);
                                lastFiscal.put("auth", auth);
                                lastFiscal.put("fiscal_time", fiscalTime);
                                tableRef.child("last_fiscal_info").setValue(lastFiscal);

                                DatabaseReference marksRef = tableRef.child("epsilon_marks").push();
                                Map<String, Object> markData = new HashMap<>();
                                markData.put("mark", mark);
                                markData.put("uid", uid);
                                markData.put("auth", auth);
                                markData.put("qrUrl", qr);
                                markData.put("timestamp", System.currentTimeMillis());
                                marksRef.setValue(markData);

                                // Update status to "printed"
                                Map<String, Object> statusUpdate = new HashMap<>();
                                statusUpdate.put("status", "printed");
                                tableRef.child("current_order").updateChildren(statusUpdate);

                                // Send receipt to printer with VAT info (χρησιμοποιείται η fiscalTime)
                                send86ReceiptToPrinter(data.tableNumber, originalItems, mark, uid, auth, qr, fiscalTime);

                                Toast.makeText(ActiveTablesActivity.this, "Επιτυχής έκδοση 8.6! MARK: " + mark, Toast.LENGTH_LONG).show();
                            }

                            @Override
                            public void onError(String message) {
                                Toast.makeText(ActiveTablesActivity.this, "Σφάλμα έκδοσης 8.6: " + message, Toast.LENGTH_LONG).show();
                            }
                        });
            }

            private List<Map<String, Object>> extractAllItemsWithVat(Map<String, Object> tableData) {
                List<Map<String, Object>> allItems = new ArrayList<>();
                if (tableData == null) return allItems;

                // 1. From push-keys (normal tables)
                for (Map.Entry<String, Object> entry : tableData.entrySet()) {
                    if (entry.getKey().equals("last_fiscal_info") ||
                            entry.getKey().equals("epsilon_marks") ||
                            entry.getKey().equals("current_order")) continue;
                    if (!(entry.getValue() instanceof Map)) continue;
                    Map<String, Object> order = (Map<String, Object>) entry.getValue();
                    Object itemsObj = order.get("items");
                    if (itemsObj instanceof List) {
                        allItems.addAll((List<Map<String, Object>>) itemsObj);
                    }
                }

                // 2. From current_order (TakeAway/Delivery)
                if (allItems.isEmpty() && tableData.containsKey("current_order")) {
                    Object curObj = tableData.get("current_order");
                    if (curObj instanceof Map) {
                        Map<String, Object> cur = (Map<String, Object>) curObj;
                        Object itemsObj = cur.get("items");
                        if (itemsObj instanceof List) {
                            allItems.addAll((List<Map<String, Object>>) itemsObj);
                        }
                    }
                }

                return allItems; // without grouping, so VAT is preserved per line
            }

            private void send86ReceiptToPrinter(String tableNumber, List<Map<String, Object>> items,
                                                long mark, String uid, String auth, String qrUrl, String fiscalTime) {
                Map<String, Object> receiptData = new HashMap<>();
                receiptData.put("tableNumber", tableNumber);
                receiptData.put("items", items);
                receiptData.put("timestamp", System.currentTimeMillis()); // optional fallback
                receiptData.put("type", "order_slip_86");
                receiptData.put("target", "RECEIPT");
                receiptData.put("epsilon_mark", String.valueOf(mark));
                receiptData.put("epsilon_uid", uid);
                receiptData.put("epsilon_auth", auth);
                receiptData.put("epsilon_qr", qrUrl);
                receiptData.put("totalAmount", calculateTotalAmountFromItems(items));
                receiptData.put("fiscal_time", fiscalTime);   // ← νέο πεδίο

                DatabaseReference receiptsRef = FirebaseHelper.getReference("receipts");
                receiptsRef.child(tableNumber + "_86_" + System.currentTimeMillis()).setValue(receiptData);
            }

            private double calculateTotalAmountFromItems(List<Map<String, Object>> items) {
                double total = 0;
                for (Map<String, Object> item : items) {
                    double price = ((Number) item.get("price")).doubleValue();
                    int qty = ((Number) item.get("quantity")).intValue();
                    total += price * qty;
                }
                return total;
            }

            @Override
            public void onCancelClicked(ActiveTableAdapter.TableCardData data) {
                if (orderOnlyMode) {
                    // Simple local clear without any Epsilon call
                    clearTableAndMergedSource(data.tableNumber, () ->
                            Toast.makeText(ActiveTablesActivity.this,
                                    "Το τραπέζι " + data.tableNumber + " άδειασε (λειτουργία μόνο παραγγελιών)",
                                    Toast.LENGTH_SHORT).show()
                    );
                    return;
                }
                // Normal mode logic
                if ("ordered".equals(data.status)) {
                    clearTableAndMergedSource(data.tableNumber, () ->
                            Toast.makeText(ActiveTablesActivity.this,
                                    "Το τραπέζι " + data.tableNumber + " ακυρώθηκε τοπικά (χωρίς διαβίβαση)", Toast.LENGTH_SHORT).show()
                    );
                } else {
                    new AlertDialog.Builder(ActiveTablesActivity.this)
                            .setTitle("Ακύρωση Σημασμένου Τραπεζιού")
                            .setMessage("Το τραπέζι έχει ήδη εκτυπωθεί/σημανθεί. Θα εκδοθεί Ακυρωτικό Δελτίο 8.6 στην ΑΑΔΕ. Θέλετε να προχωρήσετε;")
                            .setPositiveButton("ΝΑΙ, ΑΚΥΡΩΣΗ", (dialog, which) -> {
                                List<Map<String, Object>> itemsToCancel = extractAllItems(data.tableData);
                                Toast.makeText(ActiveTablesActivity.this, "Διαβίβαση Ακυρωτικού 8.6...", Toast.LENGTH_SHORT).show();
                                EpsilonIntegrationHelper.cancelOrderSlip86(ActiveTablesActivity.this, data.tableNumber, itemsToCancel,
                                        new EpsilonIntegrationHelper.CallbackWithResult<SendResponse>() {
                                            @Override
                                            public void onSuccess(SendResponse result) {
                                                clearTableAndMergedSource(data.tableNumber, () -> {
                                                    saveToHistory("cancel", data.tableNumber, 0.0, null, "Ακύρωση παραγγελίας 8.6 (MARK: " + result.getMark() + ")");
                                                    Toast.makeText(ActiveTablesActivity.this,
                                                            "Επιτυχής Ακύρωση! Εκδόθηκε MARK: " + result.getMark(), Toast.LENGTH_LONG).show();
                                                });
                                            }
                                            @Override
                                            public void onError(String message) {
                                                Toast.makeText(ActiveTablesActivity.this,
                                                        "Αποτυχία Ακύρωσης Παρόχου: " + message, Toast.LENGTH_LONG).show();
                                            }
                                        });
                            })
                            .setNegativeButton("ΟΧΙ", null)
                            .show();
                }
            }

            private void performLegalCancellation(ActiveTableAdapter.TableCardData data) {
                List<Map<String, Object>> items = extractAllItems(data.tableData);
                EpsilonIntegrationHelper.sendOrderSlip86(ActiveTablesActivity.this, data.tableNumber, items, true, new EpsilonIntegrationHelper.CallbackWithResult<SendResponse>() {
                    @Override
                    public void onSuccess(SendResponse result) {
                        clearTableAndMergedSource(data.tableNumber, () -> {
                            Toast.makeText(ActiveTablesActivity.this, "Το παραστατικό ακυρώθηκε νόμιμα (MARK: " + result.getMark() + ")", Toast.LENGTH_LONG).show();
                        });
                    }
                    @Override
                    public void onError(String message) {
                        Toast.makeText(ActiveTablesActivity.this, "Αποτυχία Ακύρωσης: " + message, Toast.LENGTH_LONG).show();
                    }
                });
            }

            private void unmergeTable(String tableNumber) {
                billsRef.child(tableNumber).child("current_order").addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (!snapshot.exists()) {
                            Toast.makeText(ActiveTablesActivity.this, "Το τραπέζι δεν έχει παραγγελία", Toast.LENGTH_SHORT).show();
                            resetUnmergeMode();
                            return;
                        }
                        Map<String, Object> curOrder = (Map<String, Object>) snapshot.getValue();
                        if (curOrder == null || !curOrder.containsKey("merged_from")) {
                            Toast.makeText(ActiveTablesActivity.this, "Το τραπέζι δεν είναι συγχωνευμένο", Toast.LENGTH_SHORT).show();
                            resetUnmergeMode();
                            return;
                        }
                        String mergedFrom = (String) curOrder.get("merged_from");
                        billsRef.child(tableNumber).child("current_order").child("merged_from").removeValue()
                                .addOnSuccessListener(aVoid -> {
                                    billsRef.child(mergedFrom).removeValue()
                                            .addOnSuccessListener(aVoid2 -> {
                                                saveToHistory(HistoryEntry.TYPE_TABLE_MOVED,
                                                        tableNumber + " διασπάστηκε από " + mergedFrom, 0.0, null,
                                                        "Διάσπαση τραπεζιών");
                                                Toast.makeText(ActiveTablesActivity.this,
                                                        "Το τραπέζι " + tableNumber + " διασπάστηκε από το " + mergedFrom,
                                                        Toast.LENGTH_SHORT).show();
                                                resetUnmergeMode();
                                            })
                                            .addOnFailureListener(e -> {
                                                Toast.makeText(ActiveTablesActivity.this, "Σφάλμα καθαρισμού πηγής", Toast.LENGTH_SHORT).show();
                                                resetUnmergeMode();
                                            });
                                })
                                .addOnFailureListener(e -> {
                                    Toast.makeText(ActiveTablesActivity.this, "Σφάλμα ενημέρωσης τραπεζιού", Toast.LENGTH_SHORT).show();
                                    resetUnmergeMode();
                                });
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        resetUnmergeMode();
                    }
                });
            }

            private void resetUnmergeMode() {
                isUnmergeMode = false;
                btnUnmergeTables.setText("Διάσπαση");
                btnMergeTables.setVisibility(View.VISIBLE);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onPayClicked(ActiveTableAdapter.TableCardData data) {
                if (orderOnlyMode) {
                    Toast.makeText(ActiveTablesActivity.this, "Οι πληρωμές είναι απενεργοποιημένες στη λειτουργία μόνο παραγγελιών", Toast.LENGTH_SHORT).show();
                    return;
                }

                if ("ordered".equals(data.status)) {
                    new AlertDialog.Builder(ActiveTablesActivity.this)
                            .setTitle("Εκκρεμεί Έκδοση (8.6)")
                            .setMessage("Το τραπέζι δεν έχει σημανθεί.\nΑν πρόκειται για λάθος και η παραγγελία είναι Take Away, μπορείτε να εκδώσετε απευθείας Απόδειξη Λιανικής (11.2) προχωρώντας στην πληρωμή.")
                            .setPositiveButton("📝 Έκδοση 8.6", (dialog, which) -> issue86ForOrangeTable(data))
                            .setNegativeButton("🛍️ Πληρωμή (11.2)", (dialog, which) -> proceedToPayment(data))
                            .setNeutralButton("Ακύρωση", null)
                            .show();
                    return;
                }

                proceedToPayment(data);
            }

            private void proceedToPayment(ActiveTableAdapter.TableCardData data) {
                pendingTableData = data.tableData;
                pendingTableNumber = data.tableNumber;
                pendingOrderDetails = data.details;
                double totalAmount = calculateTotalAmount(data.tableData);
                pendingAmount = totalAmount;
                pendingPaymentItems = extractAllItems(data.tableData);
                new AlertDialog.Builder(ActiveTablesActivity.this)
                        .setTitle("Διαχωρισμός Λογαριασμού;")
                        .setMessage("Το συνολικό ποσό είναι €" + String.format("%.2f", totalAmount) +
                                "\n\nΘέλετε να διαχωρίσετε τον λογαριασμό;")
                        .setPositiveButton("Ναι, διαχωρισμός", (dialog, which) -> {
                            CurrentTableHolder.set(data.tableNumber, data.tableData);
                            Intent intent = new Intent(ActiveTablesActivity.this, SplitItemsActivity.class);
                            intent.putExtra("table_number", data.tableNumber);
                            intent.putExtra("is_partial", false);
                            startActivityForResult(intent, REQUEST_SPLIT_ITEMS);
                        })
                        .setNegativeButton("Όχι, ολόκληρο", (dialog, which) -> {
                            showPaymentMethodDialog("Ολόκληρο το ποσό (€" + String.format("%.2f", totalAmount) + ")",
                                    () -> clearTableAndMergedSource(data.tableNumber, () ->
                                            Toast.makeText(ActiveTablesActivity.this,
                                                    "Ο λογαριασμός εξοφλήθηκε!", Toast.LENGTH_SHORT).show()
                                    ));
                        })
                        .show();
            }

            @Override
            public void onPartialClicked(ActiveTableAdapter.TableCardData data) {
                if (orderOnlyMode) {
                    Toast.makeText(ActiveTablesActivity.this, "Οι μερικές πληρωμές είναι απενεργοποιημένες", Toast.LENGTH_SHORT).show();
                    return;
                }
                pendingTableData = data.tableData;
                pendingTableNumber = data.tableNumber;
                double totalAmount = calculateTotalAmount(data.tableData);
                CurrentTableHolder.set(data.tableNumber, data.tableData);
                Intent intent = new Intent(ActiveTablesActivity.this, SplitItemsActivity.class);
                intent.putExtra("table_number", data.tableNumber);
                intent.putExtra("is_partial", true);
                startActivityForResult(intent, REQUEST_SPLIT_ITEMS_PARTIAL);
            }

            @Override
            public void onMoveClicked(ActiveTableAdapter.TableCardData data) {
                // (Δεν υπάρχει πλέον έλεγχος orderOnlyMode)
                if (isMergeMode) {
                    Toast.makeText(ActiveTablesActivity.this, "Βγείτε πρώτα από την κατάσταση συγχώνευσης", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!isMoveMode) {
                    isMoveMode = true;
                    showAllTablesForMove = true;
                    moveSourceTable = null;
                    loadActiveTables();
                    Toast.makeText(ActiveTablesActivity.this, "Επιλέξτε το τραπέζι ΠΗΓΗ προς μετακίνηση", Toast.LENGTH_SHORT).show();
                } else {
                    isMoveMode = false;
                    showAllTablesForMove = false;
                    moveSourceTable = null;
                    loadActiveTables();
                    Toast.makeText(ActiveTablesActivity.this, "Μετακίνηση ακυρώθηκε", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onPrintTempClicked(ActiveTableAdapter.TableCardData data) {
                if (orderOnlyMode) {
                    Toast.makeText(ActiveTablesActivity.this, "Η εκτύπωση προσωρινής απόδειξης είναι απενεργοποιημένη",
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                if ("ordered".equals(data.status)) {
                    // Instead of printing a local temporary receipt, issue an official 8.6 Order Slip
                    issue86ForOrangeTable(data);
                } else {
                    // For tables already printed, just reprint the last fiscal receipt
                    performPrintTempReceipt(data);
                }
            }

            private void performPrintTempReceipt(ActiveTableAdapter.TableCardData data) {
                List<String> accumulatedMarks = new ArrayList<>();
                for (Map.Entry<String, Object> entry : data.tableData.entrySet()) {
                    if (entry.getValue() instanceof Map) {
                        Map<String, Object> order = (Map<String, Object>) entry.getValue();
                        if (order.containsKey("epsilon_86_mark")) {
                            accumulatedMarks.add(String.valueOf(order.get("epsilon_86_mark")));
                        }
                    }
                }

                data.tableData.put("accumulated_marks", accumulatedMarks);
                sendTempReceiptToPrinter(data.tableNumber, data.tableData);
                Toast.makeText(ActiveTablesActivity.this, "Εκτύπωση Προσωρινής Αναφοράς", Toast.LENGTH_SHORT).show();
            }

            private void proceedToPaymentForOrangeTable(ActiveTableAdapter.TableCardData data) {
                pendingTableData = data.tableData;
                pendingTableNumber = data.tableNumber;
                pendingOrderDetails = data.details;
                double totalAmount = calculateTotalAmount(data.tableData);
                pendingAmount = totalAmount;
                pendingPaymentItems = extractAllItems(data.tableData);
                new AlertDialog.Builder(ActiveTablesActivity.this)
                        .setTitle("Διαχωρισμός Λογαριασμού;")
                        .setMessage("Το συνολικό ποσό είναι €" + String.format("%.2f", totalAmount) +
                                "\n\nΘέλετε να διαχωρίσετε τον λογαριασμό;")
                        .setPositiveButton("Ναι, διαχωρισμός", (dialog, which) -> {
                            CurrentTableHolder.set(data.tableNumber, data.tableData);
                            Intent intent = new Intent(ActiveTablesActivity.this, SplitItemsActivity.class);
                            intent.putExtra("table_number", data.tableNumber);
                            intent.putExtra("is_partial", false);
                            startActivityForResult(intent, REQUEST_SPLIT_ITEMS);
                        })
                        .setNegativeButton("Όχι, ολόκληρο", (dialog, which) -> {
                            showPaymentMethodDialog("Ολόκληρο το ποσό (€" + String.format("%.2f", totalAmount) + ")",
                                    () -> clearTableAndMergedSource(data.tableNumber, () ->
                                            Toast.makeText(ActiveTablesActivity.this,
                                                    "Ο λογαριασμός εξοφλήθηκε!", Toast.LENGTH_SHORT).show()
                                    ));
                        })
                        .show();
            }

            @Override
            public void onTableLongClicked(ActiveTableAdapter.TableCardData data) {
                if (!data.isEmpty) {
                    Intent intent = new Intent(ActiveTablesActivity.this, TableOrderActivity.class);
                    intent.putExtra("table_number", data.tableNumber);
                    startActivity(intent);
                }
            }
        });

        tablesRecyclerView.setAdapter(adapter);

        etSearchTable = findViewById(R.id.etSearchTable);
        etSearchTable.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchQuery = s.toString().trim();
                if (searchRunnable != null) {
                    searchHandler.removeCallbacks(searchRunnable);
                }
                searchRunnable = () -> loadActiveTables();
                searchHandler.postDelayed(searchRunnable, 300);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        loadActiveTables();
        btnMergeTables = findViewById(R.id.btnMergeTables);
        btnMergeTables.setOnClickListener(v -> {
            if (!isMergeMode) {
                isMergeMode = true;
                sourceTable = null;
                if (selectedSourceCard != null) {
                    selectedSourceCard.setCardBackgroundColor(Color.WHITE);
                    selectedSourceCard = null;
                }
                btnMergeTables.setText("Ακύρωση Συγχώνευσης");
                Toast.makeText(this, "Επιλέξτε το τραπέζι ΠΗΓΗ", Toast.LENGTH_SHORT).show();
            } else {
                isMergeMode = false;
                sourceTable = null;
                if (selectedSourceCard != null) {
                    selectedSourceCard.setCardBackgroundColor(Color.WHITE);
                    selectedSourceCard = null;
                }
                btnMergeTables.setText("Συγχώνευση");
                Toast.makeText(this, "Συγχώνευση ακυρώθηκε", Toast.LENGTH_SHORT).show();
            }
        });
        btnShowEmpty = findViewById(R.id.btnShowEmpty);
        btnShowEmpty.setOnClickListener(v -> {
            showOnlyEmpty = !showOnlyEmpty;
            btnShowEmpty.setBackgroundColor(showOnlyEmpty ? Color.parseColor("#4CAF50") : Color.parseColor("#9E9E9E"));
            btnShowEmpty.setText(showOnlyEmpty ? " Άδεια (ON)" : " Άδεια");
            loadActiveTables();
        });
    }

    private List<Map<String, Object>> extractAllItems(Map<String, Object> tableData) {
        List<Map<String, Object>> allItems = new ArrayList<>();
        if (tableData == null) return allItems;

        // 1. Από push‑keys (παλιά δομή)
        for (Map.Entry<String, Object> entry : tableData.entrySet()) {
            if (entry.getKey().equals("last_fiscal_info") || entry.getKey().equals("epsilon_marks") || entry.getKey().equals("current_order"))
                continue;
            if (!(entry.getValue() instanceof Map)) continue;
            Map<String, Object> order = (Map<String, Object>) entry.getValue();
            Object itemsObj = order.get("items");
            if (itemsObj instanceof List) {
                allItems.addAll((List<Map<String, Object>>) itemsObj);
            }
        }

        // 2. Από current_order (συγχωνεύσεις, order‑only, takeaway/delivery)
        if (tableData.containsKey("current_order")) {
            Object curObj = tableData.get("current_order");
            if (curObj instanceof Map) {
                Map<String, Object> cur = (Map<String, Object>) curObj;
                Object itemsObj = cur.get("items");
                if (itemsObj instanceof List) {
                    allItems.addAll((List<Map<String, Object>>) itemsObj);
                }
            }
        }

        // Ομαδοποίηση (ίδιος κώδικας που υπήρχε)
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> item : allItems) {
            String name = (String) item.get("name");
            String comment = item.containsKey("comment") ? (String) item.get("comment") : "";
            double price = ((Number) item.get("price")).doubleValue();
            double vatPercent = item.containsKey("vatPercent") ? ((Number) item.get("vatPercent")).doubleValue() : 13.0;
            String key = name + "_" + comment + "_" + price + "_" + vatPercent;
            if (grouped.containsKey(key)) {
                Map<String, Object> existing = grouped.get(key);
                int oldQty = ((Number) existing.get("quantity")).intValue();
                int newQty = ((Number) item.get("quantity")).intValue();
                existing.put("quantity", oldQty + newQty);
            } else {
                grouped.put(key, new HashMap<>(item));
            }
        }
        return new ArrayList<>(grouped.values());
    }

    private void saveToHistory(String type, String tableNumber, double amount,
                               String paymentMethod, String details) {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        String deviceName = prefs.getString(SettingsActivity.KEY_DEVICE_NAME, "Άγνωστη συσκευή");
        DatabaseReference historyRef = FirebaseHelper.getReference("history");
        String id = historyRef.push().getKey();
        HistoryEntry entry = new HistoryEntry(type, tableNumber, amount, paymentMethod,
                deviceName, System.currentTimeMillis(), details);
        historyRef.child(id).setValue(entry);
    }

    private void loadActiveTables() {
        if (activeTablesListener != null) {
            billsRef.removeEventListener(activeTablesListener);
        }
        activeTablesListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String query = currentSearchQuery.trim();
                List<ActiveTableAdapter.TableCardData> list = new ArrayList<>();
                if (showAllTablesForMove) {
                    for (int i = 1; i <= settingsManager.getMaxTables(); i++) {
                        String tableNumber = String.valueOf(i);
                        if (!query.isEmpty() && !tableNumber.contains(query)) continue;
                        DataSnapshot tableSnapshot = snapshot.child(tableNumber);
                        Map<String, Object> tableData = null;
                        String details = "Κενό τραπέζι";
                        boolean isEmpty = true;
                        String status = "pending";
                        String customTitle = null;

                        if (tableSnapshot.exists()) {
                            tableData = (Map<String, Object>) tableSnapshot.getValue();
                            if (tableData != null && tableData.containsKey("merged_to") && !tableData.containsKey("current_order")) {
                                continue;
                            }
                            details = buildTableDetails(tableSnapshot);
                            if (details.isEmpty()) details = "Καμία παραγγελία";
                            isEmpty = false;
                            if (tableData != null && tableData.containsKey("current_order")) {
                                Map<String, Object> cur = (Map<String, Object>) tableData.get("current_order");
                                if (cur != null) {
                                    if (cur.containsKey("status")) {
                                        status = (String) cur.get("status");
                                    }
                                    if (cur.containsKey("merged_from")) {
                                        String mergedFrom = (String) cur.get("merged_from");
                                        String part1 = tableNumber;
                                        String part2 = mergedFrom;
                                        if (Integer.parseInt(part1) > Integer.parseInt(part2)) {
                                            String temp = part1;
                                            part1 = part2;
                                            part2 = temp;
                                        }
                                        customTitle = "Τραπέζια " + part1 + " και " + part2;
                                    }
                                }
                            }
                        }
                        list.add(new ActiveTableAdapter.TableCardData(
                                tableNumber, details, tableData, status, isEmpty, customTitle));
                    }
                } else if (showOnlyEmpty) {
                    for (int i = 1; i <= settingsManager.getMaxTables(); i++) {
                        String tableNumber = String.valueOf(i);
                        if (!query.isEmpty() && !tableNumber.contains(query)) continue;
                        if (snapshot.hasChild(tableNumber)) {
                            DataSnapshot childSnap = snapshot.child(tableNumber);
                            Map<String, Object> data = (Map<String, Object>) childSnap.getValue();
                            if (data != null && (data.containsKey("current_order") || data.containsKey("merged_to"))) {
                                continue;
                            }
                        }
                        list.add(new ActiveTableAdapter.TableCardData(
                                tableNumber, "Κενό τραπέζι", null, "pending", true, null));
                    }
                } else {
                    for (DataSnapshot tableSnapshot : snapshot.getChildren()) {
                        String tableNumber = tableSnapshot.getKey();
                        if (!query.isEmpty() && !tableNumber.contains(query)) continue;
                        Map<String, Object> tableData = (Map<String, Object>) tableSnapshot.getValue();
                        if (tableData != null && tableData.containsKey("merged_to") && !tableData.containsKey("current_order")) {
                            continue;
                        }
                        String details = buildTableDetails(tableSnapshot);
                        String status = "pending";
                        String customTitle = null;

                        if (tableData != null && tableData.containsKey("current_order")) {
                            Map<String, Object> cur = (Map<String, Object>) tableData.get("current_order");
                            if (cur != null) {
                                if (cur.containsKey("status")) {
                                    status = (String) cur.get("status");
                                }
                                if (cur.containsKey("merged_from")) {
                                    String mergedFrom = (String) cur.get("merged_from");
                                    String part1 = tableNumber;
                                    String part2 = mergedFrom;
                                    if (Integer.parseInt(part1) > Integer.parseInt(part2)) {
                                        String temp = part1;
                                        part1 = part2;
                                        part2 = temp;
                                    }
                                    customTitle = "Τραπέζια " + part1 + " και " + part2;
                                }
                            }
                        }
                        list.add(new ActiveTableAdapter.TableCardData(
                                tableNumber, details, tableData, status, false, customTitle));
                    }
                }
                adapter.submitList(list);
                if (list.isEmpty()) {
                    tablesRecyclerView.setVisibility(View.GONE);
                    tvEmpty.setVisibility(View.VISIBLE);
                } else {
                    tablesRecyclerView.setVisibility(View.VISIBLE);
                    tvEmpty.setVisibility(View.GONE);
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Σφάλμα ανάγνωσης λογαριασμών: " + error.getMessage());
            }
        };
        billsRef.addValueEventListener(activeTablesListener);
    }

    private void clearTableAndMergedSource(String tableNumber, Runnable onSuccess) {
        DatabaseReference tableRef = billsRef.child(tableNumber);

        // 1. Ανάγνωση των δεδομένων του τραπεζιού (μία φορά)
        tableRef.get().addOnSuccessListener(snapshot -> {
            Map<String, Object> updates = new HashMap<>();
            // Πάντα διαγράφουμε το ίδιο το τραπέζι
            updates.put("/" + tableNumber, null);

            // 2. Αν το τραπέζι έχει merged_from, το προσθέτουμε στη μαζική διαγραφή
            Map<String, Object> data = (Map<String, Object>) snapshot.getValue();
            if (data != null && data.containsKey("current_order")) {
                Map<String, Object> cur = (Map<String, Object>) data.get("current_order");
                if (cur != null && cur.containsKey("merged_from")) {
                    String mergedFrom = (String) cur.get("merged_from");
                    updates.put("/" + mergedFrom, null);
                }
            }

            // 3. Αναζήτηση τραπεζιών που έχουν merged_to = tableNumber (παλιός τρόπος συγχώνευσης)
            billsRef.orderByChild("merged_to").equalTo(tableNumber).get()
                    .addOnSuccessListener(sourceSnap -> {
                        for (DataSnapshot snap : sourceSnap.getChildren()) {
                            updates.put("/" + snap.getKey(), null);
                        }
                        // 4. Εκτελούμε ΟΛΕΣ τις διαγραφές ΑΤΟΜΙΚΑ
                        billsRef.getRef().updateChildren(updates)
                                .addOnSuccessListener(aVoid -> {
                                    if (onSuccess != null) onSuccess.run();
                                })
                                .addOnFailureListener(e -> {
                                    Log.e(TAG, "Multi-path delete failed", e);
                                    if (onSuccess != null) onSuccess.run();
                                });
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to query merged_to", e);
                        // Έστω και με σφάλμα, διαγράφουμε τουλάχιστον το τραπέζι
                        billsRef.getRef().updateChildren(updates)
                                .addOnSuccessListener(aVoid -> { if (onSuccess != null) onSuccess.run(); })
                                .addOnFailureListener(err -> { if (onSuccess != null) onSuccess.run(); });
                    });

        }).addOnFailureListener(e -> {
            // Αν αποτύχει ακόμα και η ανάγνωση, διαγράφουμε μόνο το τραπέζι
            Log.e(TAG, "Failed to read table data", e);
            Map<String, Object> fallbackUpdates = new HashMap<>();
            fallbackUpdates.put("/" + tableNumber, null);
            billsRef.getRef().updateChildren(fallbackUpdates)
                    .addOnSuccessListener(aVoid -> { if (onSuccess != null) onSuccess.run(); })
                    .addOnFailureListener(err -> { if (onSuccess != null) onSuccess.run(); });
        });
    }

    private String buildTableDetails(DataSnapshot tableSnapshot) {
        StringBuilder sb = new StringBuilder();
        if (tableSnapshot == null) return "Καμία παραγγελία";

        // 1. Διάβασε από current_order (προτεραιότητα)
        if (tableSnapshot.hasChild("current_order")) {
            DataSnapshot currentOrderSnap = tableSnapshot.child("current_order");
            Map<String, Object> currentOrder = (Map<String, Object>) currentOrderSnap.getValue();
            if (currentOrder != null && currentOrder.containsKey("items")) {
                Object itemsObj = currentOrder.get("items");
                if (itemsObj instanceof List) {
                    List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
                    for (Map<String, Object> item : items) {
                        String name = (String) item.get("name");
                        int qty = ((Number) item.get("quantity")).intValue();
                        sb.append("- ").append(name).append(" x").append(qty).append("\n");
                    }
                    if (sb.length() > 0) return sb.toString();
                }
            }
        }

        // 2. Αλλιώς διάβασε από τα παλιά push-keys (τυχαία κλειδιά)
        for (DataSnapshot orderSnapshot : tableSnapshot.getChildren()) {
            String key = orderSnapshot.getKey();
            if (key != null && (key.equals("last_fiscal_info") || key.equals("epsilon_marks") || key.equals("current_order"))) {
                continue;
            }
            Object value = orderSnapshot.getValue();
            if (!(value instanceof Map)) continue;
            Map<String, Object> order = (Map<String, Object>) value;
            if (order.containsKey("items") && order.get("items") instanceof List) {
                List<Map<String, Object>> items = (List<Map<String, Object>>) order.get("items");
                for (Map<String, Object> item : items) {
                    String name = (String) item.get("name");
                    int qty = ((Number) item.get("quantity")).intValue();
                    sb.append("- ").append(name).append(" x").append(qty).append("\n");
                }
            }
        }

        return sb.length() > 0 ? sb.toString() : "Καμία παραγγελία";
    }

    private void performMoveTable(String source, String destination) {
        DatabaseReference sourceRef = billsRef.child(source);
        DatabaseReference destRef = billsRef.child(destination);
        destRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot destSnapshot) {
                boolean destinationHasData = destSnapshot.exists();
                if (destinationHasData) {
                    new AlertDialog.Builder(ActiveTablesActivity.this)
                            .setTitle("Προσοχή!")
                            .setMessage("Το τραπέζι " + destination + " δεν είναι άδειο.\nΘέλετε να αντικαταστήσετε τα δεδομένα του;")
                            .setPositiveButton("Ναι, αντικατάσταση", (dialog, which) -> performActualMove(sourceRef, destRef, source, destination))
                            .setNegativeButton("Όχι", (dialog, which) -> {
                                Toast.makeText(ActiveTablesActivity.this, "Μετακίνηση ακυρώθηκε", Toast.LENGTH_SHORT).show();
                                resetMoveMode();
                            })
                            .show();
                } else {
                    performActualMove(sourceRef, destRef, source, destination);
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(ActiveTablesActivity.this, "Σφάλμα ελέγχου προορισμού: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                resetMoveMode();
            }
        });
    }

    private void performActualMove(DatabaseReference sourceRef, DatabaseReference destRef, String source, String destination) {
        sourceRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    Toast.makeText(ActiveTablesActivity.this, "Το τραπέζι πηγή δεν έχει δεδομένα", Toast.LENGTH_SHORT).show();
                    resetMoveMode();
                    return;
                }
                destRef.setValue(snapshot.getValue())
                        .addOnSuccessListener(aVoid -> {
                            saveToHistory(HistoryEntry.TYPE_TABLE_MOVED, source + " → " + destination, 0.0, null, "Μετακίνηση τραπεζιού");
                            sourceRef.removeValue()
                                    .addOnSuccessListener(aVoid2 -> {
                                        Toast.makeText(ActiveTablesActivity.this,
                                                "Το τραπέζι " + source + " μετακινήθηκε στο " + destination,
                                                Toast.LENGTH_SHORT).show();
                                        resetMoveMode();
                                    })
                                    .addOnFailureListener(e -> {
                                        Toast.makeText(ActiveTablesActivity.this,
                                                "Σφάλμα διαγραφής πηγής: " + e.getMessage(),
                                                Toast.LENGTH_SHORT).show();
                                        resetMoveMode();
                                    });
                        })
                        .addOnFailureListener(e -> {
                            Toast.makeText(ActiveTablesActivity.this,
                                    "Σφάλμα μεταφοράς: " + e.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                            resetMoveMode();
                        });
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(ActiveTablesActivity.this, "Σφάλμα: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                resetMoveMode();
            }
        });
    }

    private void resetMoveMode() {
        isMoveMode = false;
        showAllTablesForMove = false;
        moveSourceTable = null;
        if (moveSourceCard != null) {
            moveSourceCard.setCardBackgroundColor(Color.WHITE);
            moveSourceCard = null;
        }
        loadActiveTables();
    }

    private void processNextSplitPartWithRemoval(int partIndex) {
        if (partIndex >= pendingSplitPartsItems.size()) {
            // Όλα τα μέρη εξοφλήθηκαν – καθαρισμός τραπεζιού
            clearTableAndMergedSource(pendingTableNumber, () ->
                    Toast.makeText(this, "Ο λογαριασμός εξοφλήθηκε πλήρως!", Toast.LENGTH_SHORT).show()
            );
            return;
        }

        List<Map<String, Object>> partItems = pendingSplitPartsItems.get(partIndex);
        double partAmount = splitAmounts.get(partIndex);

        // Αποθήκευση των items που θα πληρωθούν
        pendingPaymentItems = partItems;
        pendingAmount = partAmount;
        pendingOrderDetails = "Μέρος " + (partIndex + 1) + " τραπεζιού " + pendingTableNumber;

        // Callback μετά την επιτυχή πληρωμή
        PaymentCompleteCallback afterPayment = () -> {
            // Αφαίρεση των πληρωμένων ειδών από το τραπέζι
            removeItemsFromTable(pendingTableNumber, convertToOrderItems(partItems), () -> {
                // Αναδρομική κλήση για το επόμενο μέρος
                processNextSplitPartWithRemoval(partIndex + 1);
            });
        };

        showPaymentMethodDialog("Μέρος " + (partIndex + 1) + " (€" + String.format("%.2f", partAmount) + ")", afterPayment);
    }

    // Βοηθητική μετατροπή Map → OrderItem (για removeItemsFromTable)
    private List<SplitItemsActivity.OrderItem> convertToOrderItems(List<Map<String, Object>> items) {
        List<SplitItemsActivity.OrderItem> list = new ArrayList<>();
        for (Map<String, Object> map : items) {
            String name = (String) map.get("name");
            int quantity = ((Number) map.get("quantity")).intValue();
            double price = ((Number) map.get("price")).doubleValue();
            String comment = (String) map.get("comment");
            double vatPercent = map.containsKey("vatPercent") ? ((Number) map.get("vatPercent")).doubleValue() : 13.0;
            list.add(new SplitItemsActivity.OrderItem(name, quantity, price, comment, vatPercent));
        }
        return list;
    }

    private double calculateTotalAmount(Map<String, Object> tableData) {
        double total = 0.0;
        if (tableData == null) return total;

        // 1. Από push‑keys
        for (Map.Entry<String, Object> entry : tableData.entrySet()) {
            if (entry.getKey().equals("last_fiscal_info") || entry.getKey().equals("epsilon_marks") || entry.getKey().equals("current_order"))
                continue;
            if (!(entry.getValue() instanceof Map)) continue;
            Map<String, Object> order = (Map<String, Object>) entry.getValue();
            Object itemsObj = order.get("items");
            if (itemsObj instanceof List) {
                List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
                for (Map<String, Object> item : items) {
                    int qty = ((Number) item.get("quantity")).intValue();
                    double price = ((Number) item.get("price")).doubleValue();
                    total += qty * price;
                }
            }
        }

        // 2. Από current_order
        if (tableData.containsKey("current_order")) {
            Object curObj = tableData.get("current_order");
            if (curObj instanceof Map) {
                Map<String, Object> cur = (Map<String, Object>) curObj;
                Object itemsObj = cur.get("items");
                if (itemsObj instanceof List) {
                    List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
                    for (Map<String, Object> item : items) {
                        int qty = ((Number) item.get("quantity")).intValue();
                        double price = ((Number) item.get("price")).doubleValue();
                        total += qty * price;
                    }
                }
            }
        }
        return total;
    }

    interface PaymentCompleteCallback {
        void onComplete();
    }

    private void addAllItemsFromTableData(Map<String, Object> tableData, List<Map<String, Object>> targetList) {
        if (tableData == null) return;
        for (Map.Entry<String, Object> entry : tableData.entrySet()) {
            if (entry.getKey().equals("last_fiscal_info") ||
                    entry.getKey().equals("epsilon_marks") ||
                    entry.getKey().equals("current_order")) continue;
            if (!(entry.getValue() instanceof Map)) continue;
            Map<String, Object> order = (Map<String, Object>) entry.getValue();
            Object itemsObj = order.get("items");
            if (itemsObj instanceof List) {
                targetList.addAll((List<Map<String, Object>>) itemsObj);
            }
        }
    }

    private void performMerge(String source, String destination) {
        DatabaseReference sourceRef = billsRef.child(source);
        DatabaseReference destRef = billsRef.child(destination);
        sourceRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot sourceSnap) {
                if (!sourceSnap.exists()) {
                    Toast.makeText(ActiveTablesActivity.this, "Το τραπέζι πηγή δεν έχει δεδομένα", Toast.LENGTH_SHORT).show();
                    resetMergeMode();
                    return;
                }
                Map<String, Object> sourceData = (Map<String, Object>) sourceSnap.getValue();
                destRef.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot destSnap) {
                        Map<String, Object> destData = destSnap.exists() ? (Map<String, Object>) destSnap.getValue() : new HashMap<>();
                        List<Map<String, Object>> combinedItems = new ArrayList<>();
                        addAllItemsFromTableData(destData, combinedItems);
                        addAllItemsFromTableData(sourceData, combinedItems);
                        Map<String, Object> newOrder = new HashMap<>();
                        newOrder.put("items", combinedItems);
                        newOrder.put("timestamp", System.currentTimeMillis());
                        newOrder.put("tableNumber", Integer.parseInt(destination));
                        newOrder.put("status", "pending");
                        newOrder.put("merged_from", source);
                        destRef.child("current_order").setValue(newOrder)
                                .addOnSuccessListener(aVoid -> {
                                    Map<String, Object> mergedFlag = new HashMap<>();
                                    mergedFlag.put("merged_to", destination);
                                    sourceRef.setValue(mergedFlag)
                                            .addOnSuccessListener(aVoid2 -> {
                                                saveToHistory(HistoryEntry.TYPE_TABLE_MERGED,
                                                        source + " → " + destination, 0.0, null,
                                                        "Συγχώνευση τραπεζιών");
                                                Toast.makeText(ActiveTablesActivity.this,
                                                        "Συγχώνευση ολοκληρώθηκε: " + source + " + " + destination,
                                                        Toast.LENGTH_SHORT).show();
                                                resetMergeMode();
                                            })
                                            .addOnFailureListener(e -> {
                                                Toast.makeText(ActiveTablesActivity.this,
                                                        "Σφάλμα μαρκαρίσματος πηγής", Toast.LENGTH_SHORT).show();
                                                resetMergeMode();
                                            });
                                })
                                .addOnFailureListener(e -> {
                                    Toast.makeText(ActiveTablesActivity.this,
                                            "Σφάλμα αποθήκευσης προορισμού", Toast.LENGTH_SHORT).show();
                                    resetMergeMode();
                                });
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        resetMergeMode();
                    }
                });
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                resetMergeMode();
            }
        });
    }

    private void resetMergeMode() {
        isMergeMode = false;
        sourceTable = null;
        if (selectedSourceCard != null) {
            selectedSourceCard.setCardBackgroundColor(Color.WHITE);
            selectedSourceCard = null;
        }
        btnMergeTables.setText("Συγχώνευση");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && paymentManager != null) {
            paymentManager.handlePosResult(resultCode, data);
        }
        if (requestCode == REQUEST_SPLIT_ITEMS && resultCode == RESULT_OK) {
            String partsJson = data.getStringExtra("split_parts");
            Type listType = new TypeToken<List<List<ProductSelectionBottomSheet.OrderItem>>>(){}.getType();
            List<List<ProductSelectionBottomSheet.OrderItem>> parts = new Gson().fromJson(partsJson, listType);
            if (parts == null || parts.isEmpty()) return;

            // Αποθήκευση όλων των μερών (για αναδρομική επεξεργασία)
            pendingSplitPartsItems = new ArrayList<>();
            splitAmounts.clear();
            for (List<ProductSelectionBottomSheet.OrderItem> part : parts) {
                double partTotal = 0;
                List<Map<String, Object>> partItems = new ArrayList<>();
                for (ProductSelectionBottomSheet.OrderItem item : part) {
                    partTotal += item.price * item.quantity;
                    Map<String, Object> map = new HashMap<>();
                    map.put("name", item.name);
                    map.put("quantity", item.quantity);
                    map.put("price", item.price);
                    map.put("comment", item.comment);
                    map.put("vatPercent", item.vatPercent);
                    partItems.add(map);
                }
                splitAmounts.add(partTotal);
                pendingSplitPartsItems.add(partItems);
            }
            // Ξεκινάμε την επεξεργασία του πρώτου μέρους
            processNextSplitPartWithRemoval(0);
        } else if (requestCode == REQUEST_SPLIT_ITEMS_PARTIAL && resultCode == RESULT_OK) {
            String partsJson = data.getStringExtra("split_parts");
            Type listType = new TypeToken<List<List<SplitItemsActivity.OrderItem>>>(){}.getType();
            List<List<SplitItemsActivity.OrderItem>> parts = new Gson().fromJson(partsJson, listType);
            if (parts == null || parts.isEmpty()) return;
            List<SplitItemsActivity.OrderItem> selectedItems = parts.get(0);

            double partTotal = 0;
            for (SplitItemsActivity.OrderItem item : selectedItems) {
                partTotal += item.price * item.quantity;
            }
            final double finalPartTotal = partTotal;
            final List<SplitItemsActivity.OrderItem> finalSelectedItems = selectedItems; // για χρήση στα lambdas

            // ✅ ΝΕΟΣ ΔΙΑΛΟΓΟΣ: επιλογή τρόπου πληρωμής για τη μερική εξόφληση
            new AlertDialog.Builder(this)
                    .setTitle("Μερική Εξόφληση - €" + String.format("%.2f", finalPartTotal))
                    .setMessage("Επιλέξτε τρόπο πληρωμής")
                    .setPositiveButton("💵 Μετρητά (χωρίς απόδειξη τώρα)", (dialog, which) -> {
                        // Αφαίρεση items και καταγραφή χωρίς φορολογικό παραστατικό
                        removeItemsFromTable(pendingTableNumber, finalSelectedItems, () -> {
                            saveToHistory(HistoryEntry.TYPE_PARTIAL_PAYMENT, pendingTableNumber, finalPartTotal,
                                    "cash", "Μερική εξόφληση με μετρητά (χωρίς απόδειξη)");
                            Toast.makeText(ActiveTablesActivity.this,
                                    "Μερική εξόφληση με μετρητά – Δεν εκτυπώθηκε απόδειξη.\n" +
                                            "Στο τέλος θα εκδοθεί μία συνολική απόδειξη.", Toast.LENGTH_LONG).show();
                            loadActiveTables();
                        });
                    })
                    .setNegativeButton("💳 Κάρτα (με απόδειξη τώρα)", (dialog, which) -> {
                        // Κανονική ροή πληρωμής με κάρτα (εκδίδει απόδειξη)
                        pendingAmount = finalPartTotal;
                        pendingPaymentItems = new ArrayList<>();
                        for (SplitItemsActivity.OrderItem item : finalSelectedItems) {
                            Map<String, Object> map = new HashMap<>();
                            map.put("name", item.name);
                            map.put("quantity", item.quantity);
                            map.put("price", item.price);
                            map.put("comment", item.comment);
                            map.put("vatPercent", item.vatPercent);
                            pendingPaymentItems.add(map);
                        }
                        isPartialPayment = true;
                        showPaymentMethodDialog("Μερική εξόφληση (€" + String.format("%.2f", finalPartTotal) + ")",
                                () -> {
                                    Toast.makeText(ActiveTablesActivity.this,
                                            "Η μερική εξόφληση με κάρτα ολοκληρώθηκε", Toast.LENGTH_SHORT).show();
                                    loadActiveTables();
                                });
                    })
                    .show();
        }
    }

    private void removeItemsFromTable(String tableNumber, List<SplitItemsActivity.OrderItem> itemsToRemove, Runnable onComplete) {
        DatabaseReference tableRef = billsRef.child(tableNumber);
        tableRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot orderSnap : snapshot.getChildren()) {
                    Map<String, Object> order = (Map<String, Object>) orderSnap.getValue();
                    if (order != null && order.containsKey("items")) {
                        List<Map<String, Object>> currentItems = (List<Map<String, Object>>) order.get("items");
                        List<Map<String, Object>> updatedItems = new ArrayList<>();
                        for (Map<String, Object> itemMap : currentItems) {
                            String name = (String) itemMap.get("name");
                            Object qtyObj = itemMap.get("quantity");
                            int qty = (qtyObj instanceof Number) ? ((Number) qtyObj).intValue() : 0;
                            String comment = (String) itemMap.get("comment");
                            if (comment == null) comment = "";
                            for (SplitItemsActivity.OrderItem toRemove : itemsToRemove) {
                                if (toRemove.name.equalsIgnoreCase(name.trim()) && toRemove.comment.trim().equalsIgnoreCase(comment.trim())) {
                                    int removeQty = toRemove.quantity;
                                    if (qty > removeQty) {
                                        qty -= removeQty;
                                        toRemove.quantity = 0;
                                    } else {
                                        qty = 0;
                                        toRemove.quantity -= qty;
                                    }
                                    break;
                                }
                            }
                            if (qty > 0) {
                                Map<String, Object> newItem = new HashMap<>(itemMap);
                                newItem.put("quantity", qty);
                                updatedItems.add(newItem);
                            }
                        }
                        if (updatedItems.isEmpty()) {
                            orderSnap.getRef().removeValue();
                        } else {
                            orderSnap.getRef().child("items").setValue(updatedItems);
                        }
                    }
                }
                onComplete.run();
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                onComplete.run();
            }
        });
    }

    private void proceedToDelivery(com.ads.paragelia.paroxos.SendResponse res) {
        String markStr = String.valueOf(res.getMark());
        String uid = res.getUid() != null ? res.getUid() : "-";
        String authCode = res.getAuthenticationCode() != null ? res.getAuthenticationCode() : "-";
        String qrUrl = res.getQrCode() != null ? res.getQrCode() : "";
        String[] options = {"🖨️ Εκτύπωση στο Ταμείο", "📱 Αποστολή με SMS", "❌ Καμία ενέργεια"};

        new AlertDialog.Builder(this)
                .setTitle("Επιτυχία! MARK: " + markStr + "\n\nΠώς θέλετε να παραδοθεί;")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        Map<String, Object> receiptData = new HashMap<>();
                        receiptData.put("tableNumber", pendingTableNumber);
                        receiptData.put("items", pendingPaymentItems);
                        receiptData.put("timestamp", System.currentTimeMillis());

                        // ΑΛΛΑΓΗ ΕΔΩ: Τολλάζουμε από "payment" σε "receipt" για να το διαβάσει
                        // το πρόγραμμα του εκτυπωτή ως αναλυτική απόδειξη.
                        receiptData.put("type", "temporary");

                        receiptData.put("target", "RECEIPT");
                        receiptData.put("epsilon_mark", markStr);
                        receiptData.put("epsilon_uid", uid);
                        receiptData.put("epsilon_auth", authCode);
                        receiptData.put("epsilon_qr", qrUrl);
                        receiptData.put("totalAmount", pendingAmount);
                        receiptData.put("paymentMethod", (pendingPaymentType == 3) ? "cash" : "card");

                        DatabaseReference receiptsRef = FirebaseHelper.getReference("receipts").child(pendingTableNumber);
                        receiptsRef.setValue(receiptData).addOnSuccessListener(aVoid -> {
                            if (!isPartialPayment) {
                                String paymentType = (pendingPaymentType == 3) ? HistoryEntry.TYPE_PAYMENT_CASH : HistoryEntry.TYPE_PAYMENT_CARD;
                                saveToHistory(paymentType, pendingTableNumber, pendingAmount,
                                        (pendingPaymentType == 3) ? "cash" : "card", pendingOrderDetails);
                            }
                            Toast.makeText(this, "Εστάλη στον εκτυπωτή αναλυτικά!", Toast.LENGTH_SHORT).show();
                            if (pendingPaymentCallback != null) {
                                pendingPaymentCallback.onComplete();
                                pendingPaymentCallback = null;
                            }
                        });
                    } else if (which == 1) {
                        showSmsDialog(pendingTableNumber, pendingOrderDetails, markStr, uid, authCode, qrUrl);
                        if (!isPartialPayment) {
                            String paymentType = (pendingPaymentType == 3) ? HistoryEntry.TYPE_PAYMENT_CASH : HistoryEntry.TYPE_PAYMENT_CARD;
                            saveToHistory(paymentType, pendingTableNumber, pendingAmount,
                                    (pendingPaymentType == 3) ? "cash" : "card", pendingOrderDetails);
                        }
                    } else if (which == 2) {
                        if (!isPartialPayment) {
                            String paymentType = (pendingPaymentType == 3) ? HistoryEntry.TYPE_PAYMENT_CASH : HistoryEntry.TYPE_PAYMENT_CARD;
                            saveToHistory(paymentType, pendingTableNumber, pendingAmount,
                                    (pendingPaymentType == 3) ? "cash" : "card", pendingOrderDetails);
                        }
                        Toast.makeText(this, "Το τραπέζι " + pendingTableNumber + " έκλεισε χωρίς άλλη ενέργεια", Toast.LENGTH_SHORT).show();
                        if (pendingPaymentCallback != null) {
                            pendingPaymentCallback.onComplete();
                            pendingPaymentCallback = null;
                        }
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void showSmsDialog(String tableNumber, String details, String mark, String uid, String authCode, String qrUrl) {
        final EditText inputPhone = new EditText(this);
        inputPhone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        inputPhone.setHint("π.χ. 69........");
        new AlertDialog.Builder(this)
                .setTitle("Αποστολή Απόδειξης")
                .setMessage("Πληκτρολογήστε το κινητό του πελάτη:")
                .setView(inputPhone)
                .setPositiveButton("Αποστολή", (dialogSms, whichSms) -> {
                    String phone = inputPhone.getText().toString().trim();
                    if (!phone.isEmpty()) {
                        String receiptText = "ΑΠΟΔΕΙΞΗ ΠΑΡΑΓΓΕΛΙΑΣ\nΤραπέζι: " + tableNumber + "\n------------------\n"
                                + details + "------------------\nMARK: " + mark
                                + "\nUID: " + uid
                                + "\nΥΠΑΕΣ: " + authCode
                                + "\nΔείτε την απόδειξη εδώ: " + qrUrl + "\nΕυχαριστούμε!";
                        if (androidx.core.content.ContextCompat.checkSelfPermission(ActiveTablesActivity.this, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            pendingPhone = phone;
                            pendingReceiptText = receiptText;
                            pendingTableNumber = tableNumber;
                            androidx.core.app.ActivityCompat.requestPermissions(ActiveTablesActivity.this, new String[]{android.Manifest.permission.SEND_SMS}, SMS_PERMISSION_CODE);
                        } else {
                            sendSmsDirectly(phone, receiptText, tableNumber);
                        }
                    }
                })
                .setNegativeButton("Ακύρωση", null)
                .show();
    }

    private void sendSmsDirectly(String phoneNumber, String receiptText, String tableNumber) {
        try {
            android.telephony.SmsManager smsManager = android.telephony.SmsManager.getDefault();
            java.util.ArrayList<String> parts = smsManager.divideMessage(receiptText);
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null);
            Toast.makeText(this, "Το SMS εστάλη!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Αποτυχία αποστολής SMS", Toast.LENGTH_SHORT).show();
        } finally {
            if (pendingPaymentCallback != null) {
                pendingPaymentCallback.onComplete();
                pendingPaymentCallback = null;
            }
        }
    }

    private void sendTempReceiptToPrinter(String tableNumber, Map<String, Object> tableData) {
        List<Map<String, Object>> allItems = extractAllItems(tableData);
        if (allItems.isEmpty()) return;
        Map<String, Object> receiptData = new HashMap<>();
        receiptData.put("tableNumber", tableNumber);
        receiptData.put("items", allItems);
        receiptData.put("timestamp", System.currentTimeMillis());
        receiptData.put("type", "temporary");

        // --- ΠΡΟΣΘΗΚΗ ΦΟΡΟΛΟΓΙΚΩΝ ΣΤΟΙΧΕΙΩΝ ΣΤΗΝ ΠΡΟΣΩΡΙΝΗ ΑΠΟΔΕΙΞΗ ---
        if (tableData.containsKey("last_fiscal_info")) {
            Map<String, Object> fiscal = (Map<String, Object>) tableData.get("last_fiscal_info");
            if (fiscal != null) {
                receiptData.put("epsilon_mark", fiscal.get("mark"));
                receiptData.put("epsilon_uid", fiscal.get("uid"));
                receiptData.put("epsilon_qr", fiscal.get("qr"));
                receiptData.put("epsilon_auth", fiscal.get("auth"));
            }
        }

        DatabaseReference receiptsRef = FirebaseHelper.getReference("receipts");
        receiptsRef.child(tableNumber).setValue(receiptData);
    }

    private void showPaymentMethodDialog(String title, PaymentCompleteCallback callback)    {
        String[] options = {"💳 Πληρωμή με Κάρτα", "💵 Πληρωμή με Μετρητά"};
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(options, (dialog, which) -> {
                    // Αποθηκεύουμε το callback για να τρέξει ΜΕΤΑ την επιλογή (Εκτύπωση/SMS)
                    pendingPaymentCallback = callback;

                    if (which == 0) {
                        pendingPaymentType = 1; // 1 = Κάρτα
                        paymentManager.startPosPayment(pendingAmount, pendingTableNumber,
                                pendingOrderDetails, pendingPaymentItems, new PaymentManager.PaymentCallback() {
                                    @Override
                                    public void onSuccess(SendResponse response) {
                                        proceedToDelivery(response);
                                    }
                                    @Override
                                    public void onError(String message) {
                                        Toast.makeText(ActiveTablesActivity.this, "Σφάλμα POS: " + message, Toast.LENGTH_SHORT).show();
                                    }
                                });
                    } else {
                        pendingPaymentType = 3; // 3 = Μετρητά
                        paymentManager.startCashPayment(pendingAmount, pendingTableNumber,
                                pendingOrderDetails, pendingPaymentItems, new PaymentManager.PaymentCallback() {
                                    @Override
                                    public void onSuccess(SendResponse response) {
                                        proceedToDelivery(response);
                                    }
                                    @Override
                                    public void onError(String message) {
                                        Toast.makeText(ActiveTablesActivity.this, "Σφάλμα Epsilon: " + message, Toast.LENGTH_SHORT).show();
                                    }
                                });
                    }
                })
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (activeTablesListener != null) {
            billsRef.removeEventListener(activeTablesListener);
        }
        settingsManager.removeListener(settingsListener);
        searchHandler.removeCallbacks(searchRunnable);
    }
}