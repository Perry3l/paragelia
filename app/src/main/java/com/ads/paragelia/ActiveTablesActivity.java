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
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ActiveTablesActivity extends BaseActivity {
    private boolean isPartialPayment = false;
    private boolean isMoveMode = false;
    public  String moveSourceTable = null;
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

    // --- ΜΕΤΑΒΛΗΤΕΣ ΓΙΑ ΤΗΝ ΠΛΗΡΩΜΗ & ΤΟ Epsilon Digital ---
    private double pendingAmount = 0.0;
    private String pendingOrderDetails = "";
    private String pendingTableNumber = "";
    private Map<String, Object> pendingTableData;
    private int pendingPaymentType = 7;
    private boolean isMergeMode = false;
    public  String sourceTable = null;
    private CardView selectedSourceCard = null;
    private Button btnMergeTables;
    private double pendingSecondAmount = 0.0;
    private String pendingSecondOrderDetails = "";
    private List<Double> splitAmounts = new ArrayList<>(); // λίστα με τα ποσά των μερών
    private int currentSplitIndex = 0;
    private static final int MAX_TABLES = 10;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // If order-only mode is enabled, do not allow access to tables management
        SharedPreferences orderPrefs = getSharedPreferences(SettingsActivity.PREFS_ORDER_MODE, MODE_PRIVATE);
        boolean orderOnlyMode = orderPrefs.getBoolean(SettingsActivity.KEY_ORDER_ONLY_MODE, false);
        if (orderOnlyMode) {
            Toast.makeText(this, "Η λειτουργία μόνο παραγγελιών είναι ενεργή.\nΔεν επιτρέπεται η διαχείριση τραπεζιών.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(R.layout.activity_active_tables);
        showMemoryOverlay();
        tablesRecyclerView = findViewById(R.id.tablesRecyclerView);
        billsRef = FirebaseHelper.getReference("active_bills");
        paymentManager = new PaymentManager(this);

        tvEmpty = findViewById(R.id.tvEmpty);
        progressBar = findViewById(R.id.progressBar);
        progressBar = findViewById(R.id.progressBar);
        tablesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ActiveTableAdapter();

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
                        adapter.notifyDataSetChanged(); // Θα βάψουμε την κάρτα αν το tableNumber είναι το moveSourceTable
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
                // ΕΛΕΓΧΟΣ: Αν το τραπέζι είναι πορτοκαλί ("ordered"), ανοίγει μενού επιλογών
                if ("ordered".equals(data.status)) {
                    String[] orangeOptions = {
                            "📝 Έκδοση 8.6 (Σήμανση στην ΑΑΔΕ)",
                            "❌ Ακύρωση Παραγγελίας",
                            "🛒 Προσθήκη/Επεξεργασία Ειδών"
                    };
                    new AlertDialog.Builder(ActiveTablesActivity.this)
                            .setTitle("Επιλογές Τραπεζιού " + data.tableNumber)
                            .setItems(orangeOptions, (dialog, which) -> {
                                if (which == 0) {
                                    // 1. Αποστολή στην Epsilon Digital για επίσημο 8.6
                                    issue86ForOrangeTable(data);
                                } else if (which == 1) {
                                    // 2. Ακαριαία τοπική ακύρωση (καλεί την υπάρχουσα μέθοδο του listener)
                                    onCancelClicked(data);
                                } else {
                                    // 3. Κανονικό άνοιγμα παραγγελίας
                                    Intent intent = new Intent(ActiveTablesActivity.this, NewOrderActivity.class);
                                    intent.putExtra("EXTRA_TABLE_NUMBER", data.tableNumber);
                                    startActivity(intent);
                                }
                            })
                            .show();
                } else {
                    // Αν είναι ήδη λευκό ("printed"), ανοίγει κανονικά την παραγγελία
                    Intent intent = new Intent(ActiveTablesActivity.this, NewOrderActivity.class);
                    intent.putExtra("EXTRA_TABLE_NUMBER", data.tableNumber);
                    startActivity(intent);
                }
            }
            }

            /**
             * ΕΚΔΟΣΗ 8.6 ΓΙΑ ΠΟΡΤΟΚΑΛΙ ΤΡΑΠΕΖΙ
             * Διαβιβάζει τα τοπικά είδη στον Πάροχο και σφραγίζει το τραπέζι στη Firebase.
             */
            private void issue86ForOrangeTable(ActiveTableAdapter.TableCardData data) {
                List<Map<String, Object>> itemsToIssue = extractAllItems(data.tableData);
                if (itemsToIssue.isEmpty()) {
                    Toast.makeText(ActiveTablesActivity.this, "Δεν βρέθηκαν είδη για αποστολή", Toast.LENGTH_SHORT).show();
                    return;
                }

                Toast.makeText(ActiveTablesActivity.this, "Διαβίβαση 8.6 στην Epsilon Digital...", Toast.LENGTH_SHORT).show();

                // Διαβίβαση με isAlreadyOpen = false (αφού ανοίγει επίσημα για πρώτη φορά)
                EpsilonIntegrationHelper.sendOrderSlip86(ActiveTablesActivity.this, data.tableNumber, itemsToIssue, false,
                        new EpsilonIntegrationHelper.CallbackWithResult<SendResponse>() {
                            @Override
                            public void onSuccess(SendResponse result) {
                                long mark = result.getMark();
                                String uid = result.getUid() != null ? result.getUid() : "";
                                String qr = result.getQrCode() != null ? result.getQrCode() : "";

                                DatabaseReference tableRef = billsRef.child(data.tableNumber);

                                // 1. Ενημέρωση κεντρικού φορολογικού δείκτη
                                Map<String, Object> lastFiscal = new HashMap<>();
                                lastFiscal.put("mark", String.valueOf(mark));
                                lastFiscal.put("uid", uid);
                                lastFiscal.put("qr", qr);
                                lastFiscal.put("fiscal_time", new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()));
                                tableRef.child("last_fiscal_info").setValue(lastFiscal);

                                // 2. Προσθήκη στο φορολογικό ιστορικό του τραπεζιού
                                DatabaseReference marksRef = tableRef.child("epsilon_marks").push();
                                Map<String, Object> markData = new HashMap<>();
                                markData.put("mark", mark);
                                markData.put("uid", uid);
                                markData.put("qrUrl", qr);
                                markData.put("timestamp", System.currentTimeMillis());
                                marksRef.setValue(markData);

                                // 3. Μετατροπή του status σε "printed" (λευκό)
                                Map<String, Object> statusUpdate = new HashMap<>();
                                statusUpdate.put("status", "printed");
                                tableRef.child("current_order").updateChildren(statusUpdate);

                                Toast.makeText(ActiveTablesActivity.this, "Επιτυχής έκδοση 8.6! MARK: " + mark, Toast.LENGTH_LONG).show();
                            }

                            @Override
                            public void onError(String message) {
                                Toast.makeText(ActiveTablesActivity.this, "Σφάλμα έκδοσης 8.6: " + message, Toast.LENGTH_LONG).show();
                            }
                        });
            }

            @Override
            public void onCancelClicked(ActiveTableAdapter.TableCardData data) {
                // ΕΛΕΓΧΟΣ: Αν το τραπέζι είναι απλά πορτοκαλί (status == "ordered"),
                // ΔΕΝ περνάει από τον Πάροχο. Γίνεται απευθείας και ακαριαία τοπική διαγραφή.
                if ("ordered".equals(data.status)) {
                    clearTableAndMergedSource(data.tableNumber, () ->
                            Toast.makeText(ActiveTablesActivity.this,
                                    "Το τραπέζι " + data.tableNumber + " ακυρώθηκε τοπικά (χωρίς διαβίβαση)", Toast.LENGTH_SHORT).show()
                    );
                } else {
                    // Διαφορετικά (έχει εκτυπωθεί επίσημη αναφορά/απόδειξη και η κάρτα είναι λευκή),
                    // στέλνουμε υποχρεωτικά το Ακυρωτικό 8.6 μέσω του Παρόχου.
                    new AlertDialog.Builder(ActiveTablesActivity.this)
                            .setTitle("Ακύρωση Σημασμένου Τραπεζιού")
                            .setMessage("Το τραπέζι έχει ήδη εκτυπωθεί/σημανθεί. Θα εκδοθεί Ακυρωτικό Δελτίο 8.6 στην ΑΑΔΕ. Θέλετε να προχωρήσετε;")
                            .setPositiveButton("ΝΑΙ, ΑΚΥΡΩΣΗ", (dialog, which) -> {
                                // Εξαγωγή όλων των ειδών για την αποστολή του ακυρωτικού
                                List<Map<String, Object>> itemsToCancel = extractAllItems(data.tableData);

                                Toast.makeText(ActiveTablesActivity.this, "Διαβίβαση Ακυρωτικού 8.6...", Toast.LENGTH_SHORT).show();

                                EpsilonIntegrationHelper.cancelOrderSlip86(ActiveTablesActivity.this, data.tableNumber, itemsToCancel,
                                        new EpsilonIntegrationHelper.CallbackWithResult<SendResponse>() {
                                            @Override
                                            public void onSuccess(SendResponse result) {
                                                // Διαγραφή από τη Firebase μόνο μετά την επιτυχή ακύρωση στην ΑΑΔΕ
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

                // FIXED: Use ActiveTablesActivity.this instead of this
                EpsilonIntegrationHelper.sendOrderSlip86(ActiveTablesActivity.this, data.tableNumber, items, true, new EpsilonIntegrationHelper.CallbackWithResult<SendResponse>() {
                    @Override
                    public void onSuccess(SendResponse result) {
                        // Delete the table only after receiving the cancellation MARK
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
                        // Αφαίρεσε το merged_from από το current_order
                        billsRef.child(tableNumber).child("current_order").child("merged_from").removeValue()
                                .addOnSuccessListener(aVoid -> {
                                    // Καθάρισε την πηγή
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
                // If the table is orange (ordered, no fiscal receipt yet)
                if ("ordered".equals(data.status)) {
                    // Show choice dialog: temporary receipt or final payment
                    new AlertDialog.Builder(ActiveTablesActivity.this)
                            .setTitle("Επιλογή ενέργειας")
                            .setMessage("Το τραπέζι δεν έχει εκδοθεί ακόμα. Τι θέλετε να κάνετε;")
                            .setPositiveButton("Προσωρινή Απόδειξη", (dialog, which) -> {
                                // Call the existing temporary receipt logic (same as "Print Temp" button)
                                onPrintTempClicked(data);
                            })
                            .setNegativeButton("Τελική Πληρωμή", (dialog, which) -> {
                                // Proceed to normal payment flow (split / full payment)
                                proceedToPayment(data);
                            })
                            .setNeutralButton("Ακύρωση", null)
                            .show();
                    return;
                }

                // For non‑orange tables (already printed) – normal payment flow
                proceedToPayment(data);
            }

            // Helper method to encapsulate the existing payment logic
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
                // Το Move ενεργοποιεί την isMoveMode (ίδια λογική με το παλιό)
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
                // Only show the choice for orange tables (no fiscal receipt yet)
                if ("ordered".equals(data.status)) {
                    new AlertDialog.Builder(ActiveTablesActivity.this)
                            .setTitle("Επιλογή Αναφοράς")
                            .setMessage("Τι θέλετε να εκτυπωθεί;")
                            .setPositiveButton("Προσωρινή Απόδειξη", (dialog, which) -> {
                                performPrintTempReceipt(data);
                            })
                            .setNegativeButton("Take Away (Τελική Πληρωμή)", (dialog, which) -> {
                                // Reuse the existing payment logic for final receipt
                                proceedToPaymentForOrangeTable(data);
                            })
                            .show();
                    return;
                }
                // For white tables (already printed) – just print the temporary receipt as before
                performPrintTempReceipt(data);
            }

            /**
             * Prints a temporary receipt (pro‑forma) without involving Epsilon Digital.
             * This is the original logic of onPrintTempClicked.
             */
            private void performPrintTempReceipt(ActiveTableAdapter.TableCardData data) {
                // Συλλέγουμε όλα τα διαθέσιμα MARK του τραπεζιού
                List<String> accumulatedMarks = new ArrayList<>();
                for (Map.Entry<String, Object> entry : data.tableData.entrySet()) {
                    if (entry.getValue() instanceof Map) {
                        Map<String, Object> order = (Map<String, Object>) entry.getValue();
                        if (order.containsKey("epsilon_86_mark")) {
                            accumulatedMarks.add(String.valueOf(order.get("epsilon_86_mark")));
                        }
                    }
                }

                Map<String, Object> update = new HashMap<>();
                update.put("status", "printed");
                billsRef.child(data.tableNumber).child("current_order").updateChildren(update)
                        .addOnSuccessListener(aVoid -> {
                            // Στέλνουμε τα είδη ΚΑΙ τη λίστα με τα επίσημα MARK στον εκτυπωτή
                            data.tableData.put("accumulated_marks", accumulatedMarks);
                            sendTempReceiptToPrinter(data.tableNumber, data.tableData);
                            Toast.makeText(ActiveTablesActivity.this, "Εκτύπωση Επίσημης Αναφοράς Τραπεζιού", Toast.LENGTH_SHORT).show();
                            loadActiveTables();
                        });
            }

            /**
             * Proceeds directly to final payment (split / full payment) for an orange table.
             * This uses the same logic as onPayClicked but without the intermediate choice dialog.
             */
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

            // ========== ΠΡΟΣΘΗΚΗ ==========
            public void onTableLongClicked(ActiveTableAdapter.TableCardData data) {
                if (!data.isEmpty) {
                    Intent intent = new Intent(ActiveTablesActivity.this, TableOrderActivity.class);
                    intent.putExtra("table_number", data.tableNumber);
                    startActivity(intent);
                }
            }
            // =============================
        });
        tablesRecyclerView.setAdapter(adapter);

        etSearchTable = findViewById(R.id.etSearchTable);
        etSearchTable.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchQuery = s.toString().trim();
                // Ακύρωσε τον προηγούμενο προγραμματισμένο έλεγχο
                if (searchRunnable != null) {
                    searchHandler.removeCallbacks(searchRunnable);
                }
                // Προγραμμάτισε νέο έλεγχο μετά από 300ms
                searchRunnable = () -> loadActiveTables();
                searchHandler.postDelayed(searchRunnable, 300);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        loadActiveTables();
        btnMergeTables = findViewById(R.id.btnMergeTables);
        btnMergeTables.setOnClickListener(v -> {
            if (!isMergeMode) {
                // Ενεργοποίηση κατάστασης συγχώνευσης
                isMergeMode = true;
                sourceTable = null;
                if (selectedSourceCard != null) {
                    selectedSourceCard.setCardBackgroundColor(Color.WHITE);
                    selectedSourceCard = null;
                }
                btnMergeTables.setText("Ακύρωση Συγχώνευσης");
                Toast.makeText(this, "Επιλέξτε το τραπέζι ΠΗΓΗ", Toast.LENGTH_SHORT).show();
            } else {
                // Ακύρωση κατάστασης
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
            btnShowEmpty.setBackgroundColor(showOnlyEmpty ? Color.parseColor("#4CAF50") : Color.parseColor("#9E9E9E"));            btnShowEmpty.setText(showOnlyEmpty ? " Άδεια (ON)" : " Άδεια");
            loadActiveTables();
        });
    }

    private List<Map<String, Object>> extractAllItems(Map<String, Object> tableData) {
        List<Map<String, Object>> allItems = new ArrayList<>();
        if (tableData == null) return allItems;

        for (Map.Entry<String, Object> entry : tableData.entrySet()) {
            // Αγνόησε ρητά τους βοηθητικούς/τεχνικούς κόμβους
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
        return allItems;
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
                    for (int i = 1; i <= MAX_TABLES; i++) {
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

                            // Παράβλεψε τραπέζια που είναι μόνο merged_to (χωρίς current_order)
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
                                    // ---------- ΝΕΟ: Σύνθετος τίτλος αν υπάρχει merged_from ----------
                                    if (cur.containsKey("merged_from")) {
                                        String mergedFrom = (String) cur.get("merged_from");
                                        String part1 = tableNumber;
                                        String part2 = mergedFrom;
                                        // Ταξινόμηση ώστε ο μικρότερος αριθμός να είναι πρώτος
                                        if (Integer.parseInt(part1) > Integer.parseInt(part2)) {
                                            String temp = part1;
                                            part1 = part2;
                                            part2 = temp;
                                        }
                                        customTitle = "Τραπέζια " + part1 + " και " + part2;
                                    }
                                    // -------------------------------------------------------------
                                }
                            }
                        }

                        list.add(new ActiveTableAdapter.TableCardData(
                                tableNumber, details, tableData, status, isEmpty, customTitle));
                    }
                } else if (showOnlyEmpty) {
                    for (int i = 1; i <= MAX_TABLES; i++) {
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
                                tableNumber, "Κενό τραπέζι", null, "pending", true,null));
                    }
                } else {
                    // Κανονική προβολή
                    for (DataSnapshot tableSnapshot : snapshot.getChildren()) {
                        String tableNumber = tableSnapshot.getKey();
                        if (!query.isEmpty() && !tableNumber.contains(query)) continue;

                        Map<String, Object> tableData = (Map<String, Object>) tableSnapshot.getValue();

                        // Παράβλεψε τραπέζια που είναι μόνο merged_to (χωρίς current_order)
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
                                // ---------- ΝΕΟ: Σύνθετος τίτλος ----------
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
        billsRef.child(tableNumber).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Map<String, Object> data = (Map<String, Object>) snapshot.getValue();
                if (data != null && data.containsKey("current_order")) {
                    Map<String, Object> cur = (Map<String, Object>) data.get("current_order");
                    if (cur != null && cur.containsKey("merged_from")) {
                        String mergedFrom = (String) cur.get("merged_from");
                        billsRef.child(mergedFrom).removeValue();
                    }
                }
                billsRef.child(tableNumber).removeValue()
                        .addOnSuccessListener(aVoid -> {
                            if (onSuccess != null) onSuccess.run();
                        });
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                billsRef.child(tableNumber).removeValue()
                        .addOnSuccessListener(aVoid -> {
                            if (onSuccess != null) onSuccess.run();
                        });
            }
        });
    }

    private String buildTableDetails(DataSnapshot tableSnapshot) {
        StringBuilder sb = new StringBuilder();
        if (tableSnapshot == null) return "Καμία παραγγελία";

        for (DataSnapshot orderSnapshot : tableSnapshot.getChildren()) {
            String key = orderSnapshot.getKey();
            if (key != null && (key.equals("last_fiscal_info") ||
                    key.equals("epsilon_marks") ||
                    key.equals("current_order"))) continue;

            Object value = orderSnapshot.getValue();
            if (!(value instanceof Map)) continue;
            Map<String, Object> order = (Map<String, Object>) value;
            if (order.containsKey("items") && order.get("items") instanceof List) {
                List<Map<String, Object>> items = (List<Map<String, Object>>) order.get("items");
                for (Map<String, Object> item : items) {
                    String name = (String) item.get("name");
                    Object qtyObj = item.get("quantity");
                    int quantity = (qtyObj instanceof Number) ? ((Number) qtyObj).intValue() : 0;
                    sb.append("- ").append(name).append(" x").append(quantity).append("\n");
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : "Καμία παραγγελία";
    }

    private void performMoveTable(String source, String destination) {
        DatabaseReference sourceRef = billsRef.child(source);
        DatabaseReference destRef = billsRef.child(destination);

        // Πρώτα ελέγχουμε αν το destination έχει ήδη δεδομένα
        destRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot destSnapshot) {
                boolean destinationHasData = destSnapshot.exists();

                if (destinationHasData) {
                    // Το destination δεν είναι άδειο – ρωτάμε τον χρήστη αν θέλει να το αντικαταστήσει
                    new AlertDialog.Builder(ActiveTablesActivity.this)
                            .setTitle("Προσοχή!")
                            .setMessage("Το τραπέζι " + destination + " δεν είναι άδειο.\nΘέλετε να αντικαταστήσετε τα δεδομένα του;")
                            .setPositiveButton("Ναι, αντικατάσταση", (dialog, which) -> {
                                // Συνέχεια της μετακίνησης
                                performActualMove(sourceRef, destRef, source, destination);
                            })
                            .setNegativeButton("Όχι", (dialog, which) -> {
                                Toast.makeText(ActiveTablesActivity.this, "Μετακίνηση ακυρώθηκε", Toast.LENGTH_SHORT).show();
                                resetMoveMode();
                            })
                            .show();
                } else {
                    // Το destination είναι άδειο – προχωράμε κανονικά
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

    // Βοηθητική μέθοδος που εκτελεί την πραγματική μεταφορά
    private void performActualMove(DatabaseReference sourceRef, DatabaseReference destRef, String source, String destination) {
        sourceRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    Toast.makeText(ActiveTablesActivity.this, "Το τραπέζι πηγή δεν έχει δεδομένα", Toast.LENGTH_SHORT).show();
                    resetMoveMode();
                    return;
                }

                // Μεταφορά όλων των δεδομένων στο destination (το setValue αντικαθιστά τα υπάρχοντα)
                destRef.setValue(snapshot.getValue())
                        .addOnSuccessListener(aVoid -> {
                            saveToHistory(HistoryEntry.TYPE_TABLE_MOVED, source + " → " + destination, 0.0, null, "Μετακίνηση τραπεζιού");
                            // Διαγραφή της πηγής
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
        loadActiveTables(); // επιστροφή στην κανονική προβολή
    }


    private void processNextSplitPart() {
        if (currentSplitIndex >= splitAmounts.size()) {
            clearTableAndMergedSource(pendingTableNumber, () ->
                    Toast.makeText(this, "Ο λογαριασμός εξοφλήθηκε πλήρως!", Toast.LENGTH_SHORT).show()
            );
            return;
        }

        double amount = splitAmounts.get(currentSplitIndex);
        pendingAmount = amount;
        // θέτουμε τα items του τρέχοντος μέρους
        if (pendingSplitPartsItems != null && currentSplitIndex < pendingSplitPartsItems.size()) {
            pendingPaymentItems = pendingSplitPartsItems.get(currentSplitIndex);
        } else {
            pendingPaymentItems = null;
        }
        pendingOrderDetails = "Μέρος " + (currentSplitIndex + 1) + " τραπεζιού " + pendingTableNumber;

        PaymentCompleteCallback nextCallback = () -> {
            currentSplitIndex++;
            processNextSplitPart();
        };

        showPaymentMethodDialog("Μέρος " + (currentSplitIndex + 1) + " (€" + String.format("%.2f", amount) + ")",
                nextCallback);
    }


    private double calculateTotalAmount(Map<String, Object> tableData) {
        double total = 0.0;
        if (tableData == null) return total;

        for (Map.Entry<String, Object> entry : tableData.entrySet()) {
            if (entry.getKey().equals("last_fiscal_info") ||
                    entry.getKey().equals("epsilon_marks") ||
                    entry.getKey().equals("current_order")) continue;

            if (!(entry.getValue() instanceof Map)) continue;
            Map<String, Object> order = (Map<String, Object>) entry.getValue();
            Object itemsObj = order.get("items");
            if (itemsObj instanceof List) {
                List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
                for (Map<String, Object> item : items) {
                    Object qtyObj = item.get("quantity");
                    int qty = (qtyObj instanceof Number) ? ((Number) qtyObj).intValue() : 1;
                    Object priceObj = item.get("price");
                    double price = (priceObj instanceof Number) ? ((Number) priceObj).doubleValue() : 0.0;
                    total += qty * price;
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
                // Διαβάζουμε και τον προορισμό
                destRef.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot destSnap) {
                        Map<String, Object> destData = destSnap.exists() ? (Map<String, Object>) destSnap.getValue() : new HashMap<>();

                        // Συνδυασμός ειδών
                        List<Map<String, Object>> combinedItems = new ArrayList<>();
                        addAllItemsFromTableData(destData, combinedItems);
                        addAllItemsFromTableData(sourceData, combinedItems);

                        // Δημιουργία νέας παραγγελίας στον προορισμό
                        Map<String, Object> newOrder = new HashMap<>();
                        newOrder.put("items", combinedItems);
                        newOrder.put("timestamp", System.currentTimeMillis());
                        newOrder.put("tableNumber", Integer.parseInt(destination));
                        newOrder.put("status", "pending");
                        newOrder.put("merged_from", source);

                        destRef.child("current_order").setValue(newOrder)
                                .addOnSuccessListener(aVoid -> {
                                    // Μαρκάρουμε την πηγή ως συγχωνευμένη
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

            splitAmounts.clear();
            pendingSplitPartsItems = new ArrayList<>();
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
            currentSplitIndex = 0;
            processNextSplitPart();
        } else if (requestCode == 1001) {
            paymentManager.handlePosResult(resultCode, data);
        }
        else if (requestCode == REQUEST_SPLIT_ITEMS_PARTIAL && resultCode == RESULT_OK) {
            String partsJson = data.getStringExtra("split_parts");
            boolean isPartial = data.getBooleanExtra("is_partial", false);
            Type listType = new TypeToken<List<List<SplitItemsActivity.OrderItem>>>(){}.getType();
            List<List<SplitItemsActivity.OrderItem>> parts = new Gson().fromJson(partsJson, listType);

            if (parts == null || parts.isEmpty()) return;

            // Παίρνουμε μόνο το πρώτο μέρος (ο χρήστης επέλεξε ένα σύνολο ειδών προς πληρωμή)
            List<SplitItemsActivity.OrderItem> selectedItems = parts.get(0);

            // Υπολογισμός ποσού
            double partTotal = 0;
            for (SplitItemsActivity.OrderItem item : selectedItems) {
                partTotal += item.price * item.quantity;
            }
            final double finalPartTotal = partTotal;
            pendingAmount = partTotal;
            pendingOrderDetails = "Μερική εξόφληση τραπεζιού " + pendingTableNumber;
            isPartialPayment = true;

            // Δημιουργία pendingPaymentItems από τα selectedItems
            pendingPaymentItems = new ArrayList<>();
            for (SplitItemsActivity.OrderItem item : selectedItems) {
                Map<String, Object> map = new HashMap<>();
                map.put("name", item.name);
                map.put("quantity", item.quantity);
                map.put("price", item.price);
                map.put("comment", item.comment);
                map.put("vatPercent", item.vatPercent);   // προϋποθέτει SplitItemsActivity.OrderItem με vatPercent
                pendingPaymentItems.add(map);
            }

            removeItemsFromTable(pendingTableNumber, selectedItems, () -> {
                showPaymentMethodDialog("Μερική Εξόφληση (€" + String.format("%.2f", finalPartTotal) + ")",
                        () -> {
                            Toast.makeText(this, "Η μερική εξόφληση ολοκληρώθηκε. Το τραπέζι παραμένει ανοιχτό.", Toast.LENGTH_LONG).show();
                            loadActiveTables();
                        });
            });
        }
    }

    private void removeItemsFromTable(String tableNumber, List<SplitItemsActivity.OrderItem> itemsToRemove, Runnable onComplete) {
        DatabaseReference tableRef = billsRef.child(tableNumber);
        tableRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                // Διατρέχουμε όλες τις παραγγελίες του τραπεζιού
                for (DataSnapshot orderSnap : snapshot.getChildren()) {
                    Map<String, Object> order = (Map<String, Object>) orderSnap.getValue();
                    if (order != null && order.containsKey("items")) {
                        List<Map<String, Object>> currentItems = (List<Map<String, Object>>) order.get("items");
                        // Αφαιρούμε τις ποσότητες
                        List<Map<String, Object>> updatedItems = new ArrayList<>();
                        for (Map<String, Object> itemMap : currentItems) {
                            String name = (String) itemMap.get("name");
                            Object qtyObj = itemMap.get("quantity");
                            int qty = (qtyObj instanceof Number) ? ((Number) qtyObj).intValue() : 0;
                            String comment = (String) itemMap.get("comment");
                            if (comment == null) comment = "";

                            // Βρίσκουμε αν αυτό το είδος υπάρχει στα itemsToRemove
                            for (SplitItemsActivity.OrderItem toRemove : itemsToRemove) {
                                if (toRemove.name.equals(name) && toRemove.comment.equals(comment)) {
                                    int removeQty = toRemove.quantity;
                                    if (qty > removeQty) {
                                        qty -= removeQty;
                                        toRemove.quantity = 0; // σημαδεύουμε ότι αφαιρέθηκε
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
                            // Αν δεν έμειναν είδη, διαγράφουμε ολόκληρο το order
                            orderSnap.getRef().removeValue();
                        } else {
                            // Ενημερώνουμε τα items
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



    // Το τελικό βήμα: Έχουμε το MARK και ρωτάμε "Εκτύπωση ή SMS;"
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
                        // Εκτύπωση
                        pendingTableData.put("epsilon_mark", markStr);
                        pendingTableData.put("epsilon_uid", uid);
                        pendingTableData.put("epsilon_auth", authCode);
                        pendingTableData.put("epsilon_qr", qrUrl);

                        DatabaseReference receiptsRef = FirebaseHelper.getReference("receipts").child(pendingTableNumber);
                        receiptsRef.setValue(pendingTableData).addOnSuccessListener(aVoid -> {
                            // Καταγραφή ιστορικού
                            if (!isPartialPayment) {
                                String paymentType = (pendingPaymentType == 3) ? HistoryEntry.TYPE_PAYMENT_CASH : HistoryEntry.TYPE_PAYMENT_CARD;
                                saveToHistory(paymentType, pendingTableNumber, pendingAmount,
                                        (pendingPaymentType == 3) ? "cash" : "card", pendingOrderDetails);
                            }
                            Toast.makeText(this, "Εστάλη στον εκτυπωτή!", Toast.LENGTH_SHORT).show();
                            if (pendingPaymentCallback != null) {
                                pendingPaymentCallback.onComplete();
                                pendingPaymentCallback = null;
                            }
                        });
                    } else if (which == 1) {
                        // SMS
                        showSmsDialog(pendingTableNumber, pendingOrderDetails, markStr, qrUrl);
                        // Η κλήση του callback θα γίνει μέσα στην sendSmsDirectly, αλλά προσθέτουμε και την καταγραφή
                        if (!isPartialPayment) {
                            String paymentType = (pendingPaymentType == 3) ? HistoryEntry.TYPE_PAYMENT_CASH : HistoryEntry.TYPE_PAYMENT_CARD;
                            saveToHistory(paymentType, pendingTableNumber, pendingAmount,
                                    (pendingPaymentType == 3) ? "cash" : "card", pendingOrderDetails);
                        }
                    } else if (which == 2) {
                        // Καμία ενέργεια
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
    // --- SMS METHODS ---
    private void showSmsDialog(String tableNumber, String details, String mark, String qrUrl) {
        final android.widget.EditText inputPhone = new android.widget.EditText(this);
        inputPhone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        inputPhone.setHint("π.χ. 69........");

        new android.app.AlertDialog.Builder(this)
                .setTitle("Αποστολή Απόδειξης")
                .setMessage("Πληκτρολογήστε το κινητό του πελάτη:")
                .setView(inputPhone)
                .setPositiveButton("Αποστολή", (dialogSms, whichSms) -> {
                    String phone = inputPhone.getText().toString().trim();
                    if (!phone.isEmpty()) {
                        String receiptText = "ΑΠΟΔΕΙΞΗ ΠΑΡΑΓΓΕΛΙΑΣ\nΤραπέζι: " + tableNumber + "\n------------------\n"
                                + details + "------------------\nMARK: " + mark + "\nΔείτε την απόδειξη εδώ: " + qrUrl + "\nΕυχαριστούμε!";

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
            android.widget.Toast.makeText(this, "Το SMS εστάλη!", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Αποτυχία αποστολής SMS", android.widget.Toast.LENGTH_SHORT).show();
        } finally {
            // Ενημερώνουμε ότι τελείωσε η διαδικασία (είτε επιτυχία είτε αποτυχία)
            if (pendingPaymentCallback != null) {
                pendingPaymentCallback.onComplete();
                pendingPaymentCallback = null;
            }
        }
    }
    private void sendTempReceiptToPrinter(String tableNumber, Map<String, Object> tableData) {
        // Αντλούμε με ασφάλεια όλα τα είδη του τραπεζιού (ανεξαρτήτως υπο-κόμβων)
        List<Map<String, Object>> allItems = extractAllItems(tableData);
        if (allItems.isEmpty()) return;

        Map<String, Object> receiptData = new HashMap<>();
        receiptData.put("tableNumber", tableNumber);
        receiptData.put("items", allItems);
        receiptData.put("timestamp", System.currentTimeMillis());
        receiptData.put("type", "temporary");

        DatabaseReference receiptsRef = FirebaseHelper.getReference("receipts");
        receiptsRef.child(tableNumber).setValue(receiptData);
    }

    private void showPaymentMethodDialog(String title, PaymentCompleteCallback callback) {
        String[] options = {"💳 Πληρωμή με Κάρτα", "💵 Πληρωμή με Μετρητά"};
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        paymentManager.startPosPayment(pendingAmount, pendingTableNumber,
                                pendingOrderDetails, pendingPaymentItems, new PaymentManager.PaymentCallback() {
                                    @Override
                                    public void onSuccess(SendResponse response) {
                                        proceedToDelivery(response);
                                        if (callback != null) callback.onComplete();
                                    }
                                    @Override
                                    public void onError(String message) {
                                        Toast.makeText(ActiveTablesActivity.this, "Σφάλμα: " + message, Toast.LENGTH_SHORT).show();
                                    }
                                });
                    } else {
                        paymentManager.startCashPayment(pendingAmount, pendingTableNumber,
                                pendingOrderDetails, pendingPaymentItems, new PaymentManager.PaymentCallback() {
                                    @Override
                                    public void onSuccess(SendResponse response) {
                                        proceedToDelivery(response);
                                        if (callback != null) callback.onComplete();
                                    }
                                    @Override
                                    public void onError(String message) {
                                        Toast.makeText(ActiveTablesActivity.this, "Σφάλμα: " + message, Toast.LENGTH_SHORT).show();
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
        searchHandler.removeCallbacks(searchRunnable);
    }
}