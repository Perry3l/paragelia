package com.ads.paragelia;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

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
    private String moveSourceTable = null;
    private CardView moveSourceCard = null;
    private static final String TAG = "ActiveTables";
    private LinearLayout tablesContainer;
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
    private Map<String, Object> pendingTableData; // Κρατάμε τα δεδομένα του τραπεζιού
    private String pendingPosUtid = "";
    private String pendingEpsilonSignature = "";
    private int pendingPaymentType = 7;
    private boolean isMergeMode = false;
    private String sourceTable = null;
    private CardView selectedSourceCard = null;
    private Button btnMergeTables;
    private double pendingSecondAmount = 0.0;
    private String pendingSecondOrderDetails = "";
    private List<Double> splitAmounts = new ArrayList<>(); // λίστα με τα ποσά των μερών
    private int currentSplitIndex = 0;
    private static final int MAX_TABLES = 10;
    private EditText etSearchTable;
    private String currentSearchQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_active_tables);

        tablesContainer = findViewById(R.id.tablesContainer);
        billsRef = FirebaseDatabase.getInstance().getReference("active_bills");

        etSearchTable = findViewById(R.id.etSearchTable);
        etSearchTable.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchQuery = s.toString().trim();
                loadActiveTables(); // Ανανέωση της λίστας με το νέο φίλτρο
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

    private void saveToHistory(String type, String tableNumber, double amount,
                               String paymentMethod, String details) {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        String deviceName = prefs.getString(SettingsActivity.KEY_DEVICE_NAME, "Άγνωστη συσκευή");

        DatabaseReference historyRef = FirebaseDatabase.getInstance().getReference("history");
        String id = historyRef.push().getKey();

        HistoryEntry entry = new HistoryEntry(type, tableNumber, amount, paymentMethod,
                deviceName, System.currentTimeMillis(), details);

        historyRef.child(id).setValue(entry);
    }

    private void loadActiveTables() {
        billsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                tablesContainer.removeAllViews();

                String query = currentSearchQuery.trim(); // όχι toLowerCase, γιατί είναι αριθμός

                if (showAllTablesForMove) {
                    // Εμφάνιση ΟΛΩΝ των τραπεζιών 1..MAX_TABLES
                    for (int i = 1; i <= MAX_TABLES; i++) {
                        String tableNumber = String.valueOf(i);

                        // Φιλτράρισμα με βάση τον αριθμό
                        if (!query.isEmpty() && !tableNumber.contains(query)) {
                            continue;
                        }

                        DataSnapshot tableSnapshot = snapshot.child(tableNumber);
                        Map<String, Object> tableData = null;
                        String details = "Κενό τραπέζι";

                        if (tableSnapshot.exists()) {
                            tableData = (Map<String, Object>) tableSnapshot.getValue();
                            details = buildTableDetails(tableSnapshot);
                            if (details.isEmpty()) details = "Καμία παραγγελία";
                        }

                        String status = "pending";
                        if (tableData != null && tableData.containsKey("current_order")) {
                            Map<String, Object> currentOrder = (Map<String, Object>) tableData.get("current_order");
                            if (currentOrder != null && currentOrder.containsKey("status")) {
                                status = (String) currentOrder.get("status");
                            }
                        }
                        createTableCard(tableNumber, details, tableData, status);
                    }
                } else {
                    if (showOnlyEmpty) {
                        // Εμφάνιση μόνο άδειων τραπεζιών 1..MAX_TABLES
                        for (int i = 1; i <= MAX_TABLES; i++) {
                            String tableNumber = String.valueOf(i);
                            if (!query.isEmpty() && !tableNumber.contains(query)) continue;

                            DataSnapshot tableSnapshot = snapshot.child(tableNumber);
                            if (tableSnapshot.exists()) continue;

                            Map<String, Object> tableData = null;
                            String details = "Κενό τραπέζι";
                            String status = "pending";
                            createTableCard(tableNumber, details, tableData, status);
                        }
                    } else {
                        // Κανονική προβολή: μόνο τα τραπέζια που ΥΠΑΡΧΟΥΝ στη βάση
                        if (!snapshot.exists()) {
                            TextView emptyView = new TextView(ActiveTablesActivity.this);
                            emptyView.setText("Δεν υπάρχουν ανοιχτά τραπέζια.");
                            tablesContainer.addView(emptyView);
                            return;
                        }

                        for (DataSnapshot tableSnapshot : snapshot.getChildren()) {
                            String tableNumber = tableSnapshot.getKey();
                            if (!query.isEmpty() && !tableNumber.contains(query)) continue;

                            Map<String, Object> tableData = (Map<String, Object>) tableSnapshot.getValue();
                            String details = buildTableDetails(tableSnapshot);
                            String status = "pending";
                            if (tableData.containsKey("current_order")) {
                                Map<String, Object> currentOrder = (Map<String, Object>) tableData.get("current_order");
                                if (currentOrder != null && currentOrder.containsKey("status")) {
                                    status = (String) currentOrder.get("status");
                                }
                            }
                            createTableCard(tableNumber, details, tableData, status);
                        }
                    }
                }

                // Αν δεν εμφανίστηκε κανένα τραπέζι
                if (tablesContainer.getChildCount() == 0) {
                    TextView emptyView = new TextView(ActiveTablesActivity.this);
                    emptyView.setText("Δεν βρέθηκαν τραπέζια με αυτόν τον αριθμό.");
                    tablesContainer.addView(emptyView);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Σφάλμα ανάγνωσης λογαριασμών: " + error.getMessage());
            }
        });
    }
    // Βοηθητική μέθοδος για την κατασκευή των λεπτομερειών του τραπεζιού
    private String buildTableDetails(DataSnapshot tableSnapshot) {
        StringBuilder sb = new StringBuilder();
        for (DataSnapshot orderSnapshot : tableSnapshot.getChildren()) {
            Map<String, Object> order = (Map<String, Object>) orderSnapshot.getValue();
            if (order != null && order.get("items") instanceof List) {
                List<Map<String, Object>> items = (List<Map<String, Object>>) order.get("items");
                for (Map<String, Object> item : items) {
                    String name = (String) item.get("name");
                    Object qtyObj = item.get("quantity");
                    int quantity = (qtyObj instanceof Long) ? ((Long) qtyObj).intValue() : (int) qtyObj;
                    sb.append("- ").append(name).append(" x").append(quantity).append("\n");
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : "Καμία παραγγελία";
    }

    private void createTableCard(String tableNumber, String details, Map<String, Object> tableData, String status) {
        final String finalTableNumber = tableNumber;
        final String finalDetails = details;
        final Map<String, Object> finalTableData = tableData;
        final boolean isEmpty = (tableData == null);

        CardView card = new CardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, 24);
        card.setLayoutParams(params);
        card.setCardElevation(8f);
        card.setRadius(12f);
        card.setContentPadding(32, 32, 32, 32);
        if (isEmpty) {
            card.setCardBackgroundColor(Color.LTGRAY);
        } else if ("ordered".equals(status)) {
            card.setCardBackgroundColor(Color.parseColor("#FFB74D")); // πορτοκαλί
        } else {
            card.setCardBackgroundColor(Color.WHITE);
        }

        LinearLayout cardContent = new LinearLayout(this);
        cardContent.setOrientation(LinearLayout.VERTICAL);

        TextView tv = new TextView(this);
        String displayDetails = isEmpty ? "Κενό τραπέζι" : finalDetails;
        tv.setText("Τραπέζι " + finalTableNumber + "\n\n" + displayDetails);
        tv.setTextSize(18f);
        tv.setTextColor(Color.BLACK);
        cardContent.addView(tv);

        // Layout για τα κουμπιά (κάθετη διάταξη)
        LinearLayout buttonsLayout = new LinearLayout(this);
        buttonsLayout.setOrientation(LinearLayout.VERTICAL);
        buttonsLayout.setPadding(0, 24, 0, 0);

        // Δημιουργία των κουμπιών μόνο αν το τραπέζι ΔΕΝ είναι άδειο
        android.widget.Button btnCancel = null;
        android.widget.Button btnPay = null;
        android.widget.Button btnPartial = null;
        android.widget.Button btnMove = null;

        if (!isEmpty) {
            if ("ordered".equals(status)) {
                // Για πορτοκαλί τραπέζια: ΕΚΤΥΠΩΣΗ ΠΡΟΣΩΡΙΝΗΣ, ΑΚΥΡΩΣΗ, ΜΕΤΑΚΙΝΗΣΗ

                // Πρώτη γραμμή: ΕΚΤΥΠΩΣΗ ΠΡΟΣΩΡΙΝΗΣ (μόνο του)
                android.widget.Button btnPrintTemp = new android.widget.Button(this);
                btnPrintTemp.setText("ΕΚΤΥΠΩΣΗ ΠΡΟΣΩΡΙΝΗΣ");
                btnPrintTemp.setBackgroundColor(Color.parseColor("#2196F3"));
                btnPrintTemp.setTextColor(Color.WHITE);
                LinearLayout.LayoutParams btnPrintParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                btnPrintParams.setMargins(0, 0, 0, 0);
                btnPrintTemp.setLayoutParams(btnPrintParams);
                buttonsLayout.addView(btnPrintTemp);

                // Δεύτερη γραμμή: ΑΚΥΡΩΣΗ | ΜΕΤΑΚΙΝΗΣΗ
                LinearLayout rowOrdered = new LinearLayout(this);
                rowOrdered.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.setMargins(0, 8, 0, 0);
                rowOrdered.setLayoutParams(rowParams);

                android.widget.Button btnCancelOrdered = new android.widget.Button(this);
                btnCancelOrdered.setText("ΑΚΥΡΩΣΗ");
                btnCancelOrdered.setBackgroundColor(Color.parseColor("#F44336"));
                btnCancelOrdered.setTextColor(Color.WHITE);
                LinearLayout.LayoutParams btnCancelParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                btnCancelParams.setMargins(0, 0, 8, 0);
                btnCancelOrdered.setLayoutParams(btnCancelParams);

                android.widget.Button btnMoveOrdered = new android.widget.Button(this);
                btnMoveOrdered.setText("ΜΕΤΑΚΙΝΗΣΗ");
                btnMoveOrdered.setBackgroundColor(Color.parseColor("#2196F3"));
                btnMoveOrdered.setTextColor(Color.WHITE);
                btnMoveOrdered.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                rowOrdered.addView(btnCancelOrdered);
                rowOrdered.addView(btnMoveOrdered);
                buttonsLayout.addView(rowOrdered);

                // Listeners
                btnPrintTemp.setOnClickListener(v -> {
                    Map<String, Object> update = new HashMap<>();
                    update.put("status", "printed");
                    billsRef.child(finalTableNumber).child("current_order").updateChildren(update)
                            .addOnSuccessListener(aVoid -> {
                                sendTempReceiptToPrinter(finalTableNumber, finalTableData);
                                Toast.makeText(this, "Η προσωρινή απόδειξη εστάλη για εκτύπωση", Toast.LENGTH_SHORT).show();
                                loadActiveTables();
                            });
                });

                btnCancelOrdered.setOnClickListener(v -> {
                    billsRef.child(finalTableNumber).removeValue()
                            .addOnSuccessListener(aVoid -> {
                                Toast.makeText(this, "Το τραπέζι " + finalTableNumber + " ακυρώθηκε", Toast.LENGTH_SHORT).show();
                            });
                });

                btnMoveOrdered.setOnClickListener(v -> {
                    if (isMergeMode) {
                        Toast.makeText(this, "Βγείτε πρώτα από την κατάσταση συγχώνευσης", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!isMoveMode) {
                        isMoveMode = true;
                        showAllTablesForMove = true;
                        moveSourceTable = null;
                        if (moveSourceCard != null) {
                            moveSourceCard.setCardBackgroundColor(Color.WHITE);
                            moveSourceCard = null;
                        }
                        loadActiveTables();
                        Toast.makeText(this, "Επιλέξτε το τραπέζι ΠΗΓΗ προς μετακίνηση", Toast.LENGTH_SHORT).show();
                    } else {
                        isMoveMode = false;
                        showAllTablesForMove = false;
                        moveSourceTable = null;
                        if (moveSourceCard != null) {
                            moveSourceCard.setCardBackgroundColor(Color.WHITE);
                            moveSourceCard = null;
                        }
                        loadActiveTables();
                        Toast.makeText(this, "Μετακίνηση ακυρώθηκε", Toast.LENGTH_SHORT).show();
                    }
                });
            }
            else {
                // ----- Πρώτη γραμμή: ΑΚΥΡΩΣΗ | ΕΚΔΟΣΗ ΑΠΟΔΕΙΞΗΣ -----
                LinearLayout row1 = new LinearLayout(this);
                row1.setOrientation(LinearLayout.HORIZONTAL);

                btnCancel = new android.widget.Button(this);
                btnCancel.setText("ΑΚΥΡΩΣΗ");
                btnCancel.setBackgroundColor(Color.parseColor("#F44336"));
                btnCancel.setTextColor(Color.WHITE);
                LinearLayout.LayoutParams btnCancelParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                btnCancelParams.setMargins(0, 0, 8, 0);
                btnCancel.setLayoutParams(btnCancelParams);

                btnPay = new android.widget.Button(this);
                btnPay.setText("ΕΚΔΟΣΗ ΑΠΟΔΕΙΞΗΣ");
                btnPay.setBackgroundColor(Color.parseColor("#4CAF50"));
                btnPay.setTextColor(Color.WHITE);
                btnPay.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                row1.addView(btnCancel);
                row1.addView(btnPay);
                buttonsLayout.addView(row1);

                // ----- Δεύτερη γραμμή: ΜΕΡΙΚΗ ΕΞΟΦΛΗΣΗ -----
                btnPartial = new android.widget.Button(this);
                btnPartial.setText("ΜΕΡΙΚΗ ΕΞΟΦΛΗΣΗ");
                btnPartial.setBackgroundColor(Color.parseColor("#FF9800"));
                btnPartial.setTextColor(Color.WHITE);
                LinearLayout.LayoutParams btnPartialParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                btnPartialParams.setMargins(0, 8, 0, 0);
                btnPartial.setLayoutParams(btnPartialParams);
                buttonsLayout.addView(btnPartial);

                // ----- Τρίτη γραμμή: ΜΕΤΑΚΙΝΗΣΗ -----
                btnMove = new android.widget.Button(this);
                btnMove.setText("ΜΕΤΑΚΙΝΗΣΗ");
                btnMove.setBackgroundColor(Color.parseColor("#2196F3"));
                btnMove.setTextColor(Color.WHITE);
                LinearLayout.LayoutParams btnMoveParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                btnMoveParams.setMargins(0, 8, 0, 0);
                btnMove.setLayoutParams(btnMoveParams);
                buttonsLayout.addView(btnMove);
            }
        }

        cardContent.addView(buttonsLayout);
        card.addView(cardContent);
        tablesContainer.addView(card);

        // --------------------- LISTENERS ΚΑΡΤΑΣ ---------------------
        card.setOnClickListener(v -> {
            if (isEmpty && !isMoveMode && !isMergeMode) {
                Intent intent = new Intent(ActiveTablesActivity.this, NewOrderActivity.class);
                intent.putExtra("EXTRA_TABLE_NUMBER", finalTableNumber);
                startActivity(intent);
                return;
            }

            if (isMoveMode) {
                if (moveSourceTable == null) {
                    if (isEmpty) {
                        Toast.makeText(this, "Δεν μπορείτε να μετακινήσετε από κενό τραπέζι", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    moveSourceTable = finalTableNumber;
                    if (moveSourceCard != null) {
                        moveSourceCard.setCardBackgroundColor(isEmpty ? Color.LTGRAY : Color.WHITE);
                    }
                    card.setCardBackgroundColor(Color.parseColor("#64B5F6"));
                    moveSourceCard = card;
                    Toast.makeText(this, "Τραπέζι " + finalTableNumber + " επιλέχθηκε ως πηγή. Επιλέξτε προορισμό.", Toast.LENGTH_SHORT).show();
                } else {
                    if (moveSourceTable.equals(finalTableNumber)) {
                        Toast.makeText(this, "Δεν μπορείτε να μετακινήσετε στο ίδιο τραπέζι", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    performMoveTable(moveSourceTable, finalTableNumber);
                }
            } else if (isMergeMode) {
                if (sourceTable == null) {
                    if (isEmpty) {
                        Toast.makeText(this, "Δεν μπορείτε να επιλέξετε κενό τραπέζι ως πηγή", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    sourceTable = finalTableNumber;
                    if (selectedSourceCard != null) {
                        selectedSourceCard.setCardBackgroundColor(isEmpty ? Color.LTGRAY : Color.WHITE);
                    }
                    card.setCardBackgroundColor(Color.parseColor("#FFD54F"));
                    selectedSourceCard = card;
                    Toast.makeText(this, "Τραπέζι " + finalTableNumber + " επιλέχθηκε ως πηγή. Επιλέξτε προορισμό.", Toast.LENGTH_SHORT).show();
                } else {
                    if (sourceTable.equals(finalTableNumber)) {
                        Toast.makeText(this, "Δεν μπορείτε να συγχωνεύσετε το ίδιο τραπέζι", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    performMerge(sourceTable, finalTableNumber);
                }
            } else {
                Intent intent = new Intent(ActiveTablesActivity.this, NewOrderActivity.class);
                intent.putExtra("EXTRA_TABLE_NUMBER", finalTableNumber);
                startActivity(intent);
            }
        });

        // --------------------- LISTENERS ΚΟΥΜΠΙΩΝ ---------------------
        // Ισχύουν μόνο για κανονικά τραπέζια (όχι ordered, όχι άδεια)
        if (!isEmpty && !"ordered".equals(status)) {
            btnMove.setOnClickListener(v -> {
                if (isMergeMode) {
                    Toast.makeText(this, "Βγείτε πρώτα από την κατάσταση συγχώνευσης", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!isMoveMode) {
                    isMoveMode = true;
                    showAllTablesForMove = true;
                    moveSourceTable = null;
                    if (moveSourceCard != null) {
                        moveSourceCard.setCardBackgroundColor(Color.WHITE);
                        moveSourceCard = null;
                    }
                    loadActiveTables();
                    Toast.makeText(this, "Επιλέξτε το τραπέζι ΠΗΓΗ προς μετακίνηση", Toast.LENGTH_SHORT).show();
                } else {
                    isMoveMode = false;
                    showAllTablesForMove = false;
                    moveSourceTable = null;
                    if (moveSourceCard != null) {
                        moveSourceCard.setCardBackgroundColor(Color.WHITE);
                        moveSourceCard = null;
                    }
                    loadActiveTables();
                    Toast.makeText(this, "Μετακίνηση ακυρώθηκε", Toast.LENGTH_SHORT).show();
                }
            });

            btnCancel.setOnClickListener(v -> {
                billsRef.child(finalTableNumber).removeValue()
                        .addOnSuccessListener(aVoid -> Toast.makeText(this,
                                "Το τραπέζι " + finalTableNumber + " ακυρώθηκε", Toast.LENGTH_SHORT).show());
            });

            btnPartial.setOnClickListener(v -> {
                pendingTableData = finalTableData;
                pendingTableNumber = finalTableNumber;
                double totalAmount = calculateTotalAmount(finalTableData);
                Intent intent = new Intent(ActiveTablesActivity.this, SplitItemsActivity.class);
                intent.putExtra("table_number", finalTableNumber);
                intent.putExtra("table_data_json", new Gson().toJson(finalTableData));
                intent.putExtra("is_partial", true);
                startActivityForResult(intent, REQUEST_SPLIT_ITEMS_PARTIAL);
            });

            btnPay.setOnClickListener(v -> {
                pendingTableData = finalTableData;
                pendingTableNumber = finalTableNumber;
                pendingOrderDetails = finalDetails;
                double totalAmount = calculateTotalAmount(finalTableData);
                pendingAmount = totalAmount;

                new AlertDialog.Builder(ActiveTablesActivity.this)
                        .setTitle("Διαχωρισμός Λογαριασμού;")
                        .setMessage("Το συνολικό ποσό είναι €" + String.format("%.2f", totalAmount) +
                                "\n\nΘέλετε να διαχωρίσετε τον λογαριασμό;")
                        .setPositiveButton("Ναι, διαχωρισμός", (dialog, which) -> {
                            Intent intent = new Intent(ActiveTablesActivity.this, SplitItemsActivity.class);
                            intent.putExtra("table_number", finalTableNumber);
                            intent.putExtra("table_data_json", new Gson().toJson(finalTableData));
                            startActivityForResult(intent, REQUEST_SPLIT_ITEMS);
                        })
                        .setNegativeButton("Όχι, ολόκληρο", (dialog, which) -> {
                            showPaymentMethodDialog("Ολόκληρο το ποσό (€" + String.format("%.2f", totalAmount) + ")",
                                    () -> {
                                        billsRef.child(finalTableNumber).removeValue();
                                        Toast.makeText(ActiveTablesActivity.this,
                                                "Ο λογαριασμός εξοφλήθηκε!", Toast.LENGTH_SHORT).show();
                                    });
                        })
                        .show();
            });
        }
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
        // Όλα τα μέρη πληρώθηκαν, διαγραφή τραπεζιού
        billsRef.child(pendingTableNumber).removeValue();
        Toast.makeText(this, "Ο λογαριασμός εξοφλήθηκε πλήρως!", Toast.LENGTH_SHORT).show();
        return;
    }

    double amount = splitAmounts.get(currentSplitIndex);
    pendingAmount = amount;
    pendingOrderDetails = "Μέρος " + (currentSplitIndex + 1) + " τραπεζιού " + pendingTableNumber;

    // Δημιουργούμε ένα callback που θα καλέσει αναδρομικά το επόμενο μέρος
    PaymentCompleteCallback nextCallback = () -> {
        currentSplitIndex++;
        processNextSplitPart();
    };

    // Εμφανίζουμε dialog επιλογής τρόπου πληρωμής για το τρέχον μέρος
    showPaymentMethodDialog("Μέρος " + (currentSplitIndex + 1) + " (€" + String.format("%.2f", amount) + ")",
            nextCallback);
}
private void showDynamicSplitDialog(double totalAmount, SplitCallback callback) {
    // Χρησιμοποιούμε μια προσωρινή λίστα και ένα AlertDialog που ενημερώνεται δυναμικά
    List<Double> parts = new ArrayList<>();
    double[] remaining = {totalAmount};

    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle("Διαχωρισμός Λογαριασμού");
    builder.setMessage("Συνολικό: €" + String.format("%.2f", totalAmount) + "\nΥπόλοιπο: €" + String.format("%.2f", remaining[0]));

    // Δημιουργούμε ένα layout για να περιέχει το EditText και τα κουμπιά
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(50, 20, 50, 20);

    EditText input = new EditText(this);
    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    input.setHint("Ποσό μέρους (€)");
    layout.addView(input);

    TextView tvParts = new TextView(this);
    tvParts.setText("Μέρη: (κανένα)");
    layout.addView(tvParts);

    builder.setView(layout);

    // Κουμπί "Προσθήκη Μέρους"
    builder.setNeutralButton("Προσθήκη", null); // θα το ορίσουμε μετά

    // Κουμπί "Εκκαθάριση Υπολοίπου"
    builder.setNegativeButton("Εκκαθάριση", (dialog, which) -> {
        if (remaining[0] > 0.01) {
            parts.add(remaining[0]);
            remaining[0] = 0.0;
        }
        callback.onSplit(parts);
    });

    // Κουμπί "Ολοκλήρωση"
    builder.setPositiveButton("Ολοκλήρωση", (dialog, which) -> {
        if (parts.isEmpty()) {
            Toast.makeText(this, "Προσθέστε τουλάχιστον ένα μέρος", Toast.LENGTH_SHORT).show();
            return;
        }
        callback.onSplit(parts);
    });

    AlertDialog dialog = builder.create();
    dialog.show();

    // Παρακάμπτουμε το κουμπί "Προσθήκη" για να μην κλείνει το dialog
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
        String str = input.getText().toString().trim();
        if (str.isEmpty()) return;
        try {
            double amount = Double.parseDouble(str);
            if (amount <= 0 || amount > remaining[0] + 0.001) {
                Toast.makeText(this, "Μη έγκυρο ποσό", Toast.LENGTH_SHORT).show();
                return;
            }
            parts.add(amount);
            remaining[0] -= amount;
            // Ενημέρωση μηνύματος και λίστας
            StringBuilder sb = new StringBuilder("Μέρη: ");
            for (int i = 0; i < parts.size(); i++) {
                sb.append("€").append(String.format("%.2f", parts.get(i)));
                if (i < parts.size() - 1) sb.append(", ");
            }
            tvParts.setText(sb.toString());
            dialog.setMessage("Συνολικό: €" + String.format("%.2f", totalAmount) +
                    "\nΥπόλοιπο: €" + String.format("%.2f", remaining[0]));
            input.setText("");
            if (remaining[0] < 0.01) {
                // Αν το υπόλοιπο μηδενίστηκε, μπορούμε να κλείσουμε αυτόματα
                callback.onSplit(parts);
                dialog.dismiss();
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Μη έγκυρο ποσό", Toast.LENGTH_SHORT).show();
        }
    });
}

interface SplitCallback {
    void onSplit(List<Double> amounts);
}
    private double calculateTotalAmount(Map<String, Object> tableData) {
        double total = 0.0;
        for (Map.Entry<String, Object> entry : tableData.entrySet()) {
            if (entry.getValue() instanceof Map) {
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
        }
        return total;
    }

    interface PaymentCompleteCallback {
        void onComplete();
    }

    private void showPaymentMethodDialog(String title, PaymentCompleteCallback callback) {
        String[] options = {"💳 Πληρωμή με Κάρτα", "💵 Πληρωμή με Μετρητά"};
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        // Πληρωμή με κάρτα
                        startPosPayment(pendingAmount, pendingTableNumber, pendingOrderDetails, callback);
                    } else {
                        // Πληρωμή με μετρητά
                        startCashPayment(pendingAmount, pendingTableNumber, pendingOrderDetails, callback);
                    }
                })
                .show();
    }

    private void showSplitAmountDialog(double totalAmount, AmountCallback callback) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("Ποσό πρώτου μέρους (€)");

        new AlertDialog.Builder(this)
                .setTitle("Διαχωρισμός Λογαριασμού")
                .setMessage("Συνολικό: €" + String.format("%.2f", totalAmount))
                .setView(input)
                .setPositiveButton("Συνέχεια", (dialog, which) -> {
                    String str = input.getText().toString().trim();
                    if (!str.isEmpty()) {
                        try {
                            double amount = Double.parseDouble(str);
                            if (amount <= 0 || amount >= totalAmount) {
                                Toast.makeText(this, "Μη έγκυρο ποσό", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            callback.onAmountEntered(amount);
                        } catch (NumberFormatException e) {
                            Toast.makeText(this, "Μη έγκυρο ποσό", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Ακύρωση", null)
                .show();
    }

    private void performMerge(String source, String destination) {
        DatabaseReference sourceRef = billsRef.child(source);
        DatabaseReference destRef = billsRef.child(destination);

        sourceRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    Toast.makeText(ActiveTablesActivity.this, "Το τραπέζι πηγή δεν έχει δεδομένα", Toast.LENGTH_SHORT).show();
                    resetMergeMode();
                    return;
                }

                // Μεταφορά όλων των παραγγελιών στο destination
                destRef.updateChildren((Map<String, Object>) snapshot.getValue())
                        .addOnSuccessListener(aVoid -> {
                            // Διαγραφή της πηγής
                            sourceRef.removeValue()
                                    .addOnSuccessListener(aVoid2 -> {
                                        saveToHistory(HistoryEntry.TYPE_TABLE_MERGED, source + " + " + destination, 0.0, null, "Συγχώνευση τραπεζιών");
                                        Toast.makeText(ActiveTablesActivity.this,
                                                "Συγχώνευση τραπεζιού " + source + " στο " + destination + " ολοκληρώθηκε",
                                                Toast.LENGTH_SHORT).show();
                                        resetMergeMode();
                                    })
                                    .addOnFailureListener(e -> {
                                        Toast.makeText(ActiveTablesActivity.this,
                                                "Σφάλμα διαγραφής πηγής: " + e.getMessage(),
                                                Toast.LENGTH_SHORT).show();
                                        resetMergeMode();
                                    });
                        })
                        .addOnFailureListener(e -> {
                            Toast.makeText(ActiveTablesActivity.this,
                                    "Σφάλμα μεταφοράς: " + e.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                            resetMergeMode();
                        });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(ActiveTablesActivity.this, "Σφάλμα: " + error.getMessage(), Toast.LENGTH_SHORT).show();
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

    private interface AmountCallback { void onAmountEntered(double amount); }
    private void showAmountDialog(String title, AmountCallback callback) {
        final android.widget.EditText inputAmount = new android.widget.EditText(this);
        inputAmount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        inputAmount.setHint("π.χ. 15.50");

        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("Πληκτρολογήστε το ποσό χρέωσης (€):")
                .setView(inputAmount)
                .setPositiveButton("ΟΚ", (dialogPos, whichPos) -> {
                    String amountStr = inputAmount.getText().toString().trim();
                    if (!amountStr.isEmpty()) {
                        try {
                            double amount = Double.parseDouble(amountStr);
                            callback.onAmountEntered(amount);
                        } catch (NumberFormatException e) {
                            android.widget.Toast.makeText(this, "Μη έγκυρο ποσό", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Ακύρωση", null)
                .show();
    }

    private void startCashPayment(double amount, String tableNumber, String orderDetails, PaymentCompleteCallback callback) {
        pendingTableNumber = tableNumber;
        pendingAmount = amount;
        pendingOrderDetails = orderDetails;
        pendingPaymentType = 3;
        pendingPaymentCallback = callback;   // <-- αποθήκευση
        Toast.makeText(this, "Αποστολή στο myDATA...", Toast.LENGTH_SHORT).show();
        sendToEpsilonProvider();
    }

    private void startPosPayment(double amount, String tableNumber, String orderDetails, PaymentCompleteCallback callback) {
        pendingTableNumber = tableNumber;
        pendingAmount = amount;
        pendingOrderDetails = orderDetails;
        pendingPaymentType = 7;
        pendingPaymentCallback = callback;
        android.widget.Toast.makeText(this, "Αναμονή έγκρισης από Epsilon...", android.widget.Toast.LENGTH_SHORT).show();

        double netAmount = Math.round((amount / 1.13) * 100.0) / 100.0;
        double vatAmount = Math.round((amount - netAmount) * 100.0) / 100.0;

        String orderRef = "TBL" + tableNumber + "_" + System.currentTimeMillis();
        String todayDate = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(new java.util.Date());

        com.google.gson.JsonObject requestPaymentObj = new com.google.gson.JsonObject();
        requestPaymentObj.addProperty("externalSystemId", orderRef);
        requestPaymentObj.addProperty("issuerVatNumber", "000000000");
        requestPaymentObj.addProperty("invoiceIssueDate", todayDate);
        requestPaymentObj.addProperty("companyBranch", "0");
        requestPaymentObj.addProperty("invoiceType", "11.2");
        requestPaymentObj.addProperty("invoiceSeries", "A");
        requestPaymentObj.addProperty("invoiceAA", String.valueOf(System.currentTimeMillis() % 100000));
        requestPaymentObj.addProperty("netValue", netAmount);
        requestPaymentObj.addProperty("vatAmount", vatAmount);
        requestPaymentObj.addProperty("totalValue", amount);
        requestPaymentObj.addProperty("paymentAmount", amount);
        requestPaymentObj.addProperty("terminalId", "22223729");
        requestPaymentObj.addProperty("nspCode", "8");

        android.content.SharedPreferences prefs = getSharedPreferences("my_prefs", MODE_PRIVATE);
        String jwtToken = prefs.getString("jwt", null);
        String dynamicBaseUrl = prefs.getString("baseUrl", null);

        if (jwtToken == null || dynamicBaseUrl == null) return;
        String fullUrl = dynamicBaseUrl + (dynamicBaseUrl.endsWith("/") ? "" : "/") + "api/requestPayment";

        com.ads.paragelia.paroxos.RetrofitClient.getInstance().getSendService()
                .requestPayment(fullUrl, "Bearer " + jwtToken, "3.0", requestPaymentObj)
                .enqueue(new retrofit2.Callback<com.google.gson.JsonObject>() {
                    @Override
                    public void onResponse(retrofit2.Call<com.google.gson.JsonObject> call, retrofit2.Response<com.google.gson.JsonObject> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            try {
                                com.google.gson.JsonObject res = response.body();
                                String uid = res.has("uid") ? res.get("uid").getAsString() : "";
                                com.google.gson.JsonObject paymentToken = res.getAsJsonObject("paymentToken");
                                String signature = paymentToken.get("signature").getAsString();
                                String timestamp = paymentToken.get("timestamp").getAsString();

                                android.content.Intent posIntent = com.ads.paragelia.paroxos.EpayHelper.createSaleIntent(
                                        getPackageName(), amount, netAmount, vatAmount, orderRef, uid, signature, "004", timestamp, "CONTACTLESS", "22223729"
                                );
                                if (posIntent != null) startActivityForResult(posIntent, 1001);
                            } catch (Exception e) {
                                android.widget.Toast.makeText(ActiveTablesActivity.this, "Σφάλμα ανάγνωσης PaymentToken", android.widget.Toast.LENGTH_LONG).show();
                            }
                        }
                    }
                    @Override public void onFailure(retrofit2.Call<com.google.gson.JsonObject> call, Throwable t) {}
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SPLIT_ITEMS && resultCode == RESULT_OK) {
            String partsJson = data.getStringExtra("split_parts");
            Type listType = new TypeToken<List<List<ProductSelectionBottomSheet.OrderItem>>>(){}.getType();
            List<List<ProductSelectionBottomSheet.OrderItem>> parts = new Gson().fromJson(partsJson, listType);

            // Μετατροπή των μερών σε λίστα ποσών και ξεκίνημα πληρωμών
            splitAmounts.clear();
            for (List<ProductSelectionBottomSheet.OrderItem> part : parts) {
                double partTotal = 0;
                for (ProductSelectionBottomSheet.OrderItem item : part) {
                    partTotal += item.price * item.quantity;
                }
                splitAmounts.add(partTotal);
            }
            currentSplitIndex = 0;
            processNextSplitPart();
        } else if (requestCode == 1001) {
            // υπάρχων κώδικας για POS
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
            final double finalPartTotal = partTotal; // <-- ΝΕΑ ΓΡΑΜΜΗ
            pendingAmount = partTotal;
            pendingOrderDetails = "Μερική εξόφληση τραπεζιού " + pendingTableNumber;
            isPartialPayment = true;
            removeItemsFromTable(pendingTableNumber, selectedItems, () -> {
                showPaymentMethodDialog("Μερική Εξόφληση (€" + String.format("%.2f", finalPartTotal) + ")", // <-- ΕΔΩ ΑΛΛΑΞΕ
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

    private void sendToEpsilonProvider() {
        android.content.SharedPreferences prefs = getSharedPreferences("my_prefs", MODE_PRIVATE);
        String jwt = prefs.getString("jwt", null);
        String baseUrl = prefs.getString("baseUrl", null);

        if (jwt == null || baseUrl == null) return;

        double netValue = Math.round((pendingAmount / 1.13) * 100.0) / 100.0;
        double vatAmount = Math.round((pendingAmount - netValue) * 100.0) / 100.0;

        com.google.gson.JsonObject sourceObj = new com.google.gson.JsonObject();
        com.google.gson.JsonObject invoiceObj = new com.google.gson.JsonObject();

        com.google.gson.JsonObject issuerObj = new com.google.gson.JsonObject();
        issuerObj.addProperty("vatNumber", "000000000");
        issuerObj.addProperty("branch", 0);
        issuerObj.addProperty("city", "ΠΟΛΗ");
        issuerObj.addProperty("country", "GR");
        invoiceObj.add("issuer", issuerObj);

        com.google.gson.JsonObject counterpartObj = new com.google.gson.JsonObject();
        counterpartObj.add("vatNumber", null);
        counterpartObj.addProperty("name", "ΠΕΛΑΤΗΣ ΛΙΑΝΙΚΗΣ");
        counterpartObj.addProperty("country", "GR");
        counterpartObj.addProperty("branch", 0);
        invoiceObj.add("counterpart", counterpartObj);

        com.google.gson.JsonObject headerObj = new com.google.gson.JsonObject();
        headerObj.addProperty("series", "A");
        headerObj.addProperty("aa", String.valueOf(System.currentTimeMillis() % 100000));
        String todayDate = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(new java.util.Date());
        headerObj.addProperty("issueDate", todayDate);
        headerObj.addProperty("invoiceType", "11.2");
        headerObj.addProperty("currency", "EUR");
        invoiceObj.add("invoiceHeader", headerObj);

        com.google.gson.JsonArray detailsArray = new com.google.gson.JsonArray();
        com.google.gson.JsonObject line1 = new com.google.gson.JsonObject();
        line1.addProperty("lineNumber", 1);
        line1.addProperty("quantity", 1);
        line1.addProperty("entityName", "Παραγγελία Τραπεζιού " + pendingTableNumber);
        line1.addProperty("netValue", netValue);
        line1.addProperty("vatCategory", 2);
        line1.addProperty("vatAmount", vatAmount);
        line1.addProperty("vatPercent", 13);
        line1.addProperty("totalValue", pendingAmount);
        line1.addProperty("measurementUnit", 1);
        line1.addProperty("classificationCategory", "category1_3");
        line1.addProperty("classificationType", "E3_561_003");
        detailsArray.add(line1);
        invoiceObj.add("invoiceDetails", detailsArray);

        com.google.gson.JsonArray paymentsArray = new com.google.gson.JsonArray();
        com.google.gson.JsonObject payment1 = new com.google.gson.JsonObject();
        payment1.addProperty("type", pendingPaymentType);
        payment1.addProperty("amount", pendingAmount);
        if (pendingPaymentType == 7) {
            payment1.addProperty("transactionId", pendingPosUtid);
            payment1.addProperty("signature", pendingEpsilonSignature);
            payment1.addProperty("tipAmount", 0.0);
        }
        paymentsArray.add(payment1);
        invoiceObj.add("paymentMethods", paymentsArray);

        com.google.gson.JsonObject summaryObj = new com.google.gson.JsonObject();
        summaryObj.addProperty("totalNetValue", netValue);
        summaryObj.addProperty("totalVatAmount", vatAmount);
        summaryObj.addProperty("totalValue", pendingAmount);
        invoiceObj.add("invoiceSummary", summaryObj);

        sourceObj.add("invoice", invoiceObj);

        com.ads.paragelia.paroxos.SendRequest sendRequest = new com.ads.paragelia.paroxos.SendRequest(
                "SYS_" + System.currentTimeMillis(), "eInvoicing", 5, sourceObj
        );

        String fullUrl = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "api/send";
        com.ads.paragelia.paroxos.RetrofitClient.getInstance().getSendService()
                .sendInvoice(fullUrl, "Bearer " + jwt, "3.0", sendRequest)
                .enqueue(new retrofit2.Callback<com.ads.paragelia.paroxos.SendResponse>() {
                    @Override
                    public void onResponse(retrofit2.Call<com.ads.paragelia.paroxos.SendResponse> call, retrofit2.Response<com.ads.paragelia.paroxos.SendResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            com.ads.paragelia.paroxos.SendResponse res = response.body();
                            if (res.getStatus() == 1 || res.getMark() > 0) {
                                proceedToDelivery(res);
                            } else if (res.getStatus() == 0) {
                                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                    checkInvoiceStatus(res.getProcessId(), res.getExternalSystemId(), pendingTableNumber);
                                }, 1000);
                            }
                        }
                    }
                    @Override public void onFailure(retrofit2.Call<com.ads.paragelia.paroxos.SendResponse> call, Throwable t) {}
                });
    }

    private void checkInvoiceStatus(String processId, String externalSystemId, String tableNumber) {
        android.content.SharedPreferences prefs = getSharedPreferences("my_prefs", MODE_PRIVATE);
        String jwtToken = prefs.getString("jwt", null);
        String baseUrl = prefs.getString("baseUrl", null);
        if (jwtToken == null || baseUrl == null) return;

        String fullUrl = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "api/get";
        com.ads.paragelia.paroxos.GetStatusRequest statusReq = new com.ads.paragelia.paroxos.GetStatusRequest(processId, externalSystemId);

        com.ads.paragelia.paroxos.RetrofitClient.getInstance().getSendService()
                .getInvoiceStatus(fullUrl, "Bearer " + jwtToken, "3.0", statusReq)
                .enqueue(new retrofit2.Callback<com.ads.paragelia.paroxos.SendResponse>() {
                    @Override
                    public void onResponse(retrofit2.Call<com.ads.paragelia.paroxos.SendResponse> call, retrofit2.Response<com.ads.paragelia.paroxos.SendResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            com.ads.paragelia.paroxos.SendResponse res = response.body();
                            if (res.getStatus() == 1) {
                                proceedToDelivery(res);
                            } else if (res.getStatus() == 0) {
                                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                    checkInvoiceStatus(processId, externalSystemId, tableNumber);
                                }, 1000);
                            }
                        }
                    }
                    @Override public void onFailure(retrofit2.Call<com.ads.paragelia.paroxos.SendResponse> call, Throwable t) {}
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

                        DatabaseReference receiptsRef = FirebaseDatabase.getInstance().getReference("receipts").child(pendingTableNumber);
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
        Map<String, Object> currentOrder = (Map<String, Object>) tableData.get("current_order");
        if (currentOrder == null) return;

        List<Map<String, Object>> items = (List<Map<String, Object>>) currentOrder.get("items");
        if (items == null) return;

        Map<String, Object> receiptData = new HashMap<>();
        receiptData.put("tableNumber", tableNumber);
        receiptData.put("items", items);
        receiptData.put("timestamp", System.currentTimeMillis());
        receiptData.put("type", "temporary");

        DatabaseReference receiptsRef = FirebaseDatabase.getInstance().getReference("receipts");
        receiptsRef.child(tableNumber).setValue(receiptData);
    }
    private String selectedItemsToString(List<SplitItemsActivity.OrderItem> items) {
        StringBuilder sb = new StringBuilder();
        for (SplitItemsActivity.OrderItem item : items) {
            sb.append(item.name).append(" x").append(item.quantity);
            if (item.comment != null && !item.comment.isEmpty()) {
                sb.append(" (").append(item.comment).append(")");
            }
            sb.append(", ");
        }
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }
    private class TableCardAdapter extends RecyclerView.Adapter<TableCardAdapter.ViewHolder> {
        private List<TableCardData> items;

        TableCardAdapter(List<TableCardData> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Δημιουργούμε μια κάρτα με τον ίδιο τρόπο που την έφτιαχνες
            CardView card = new CardView(ActiveTablesActivity.this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, 24);
            card.setLayoutParams(params);
            card.setCardElevation(8f);
            card.setRadius(12f);
            card.setContentPadding(32, 32, 32, 32);
            card.setCardBackgroundColor(Color.WHITE);
            return new ViewHolder(card);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            TableCardData data = items.get(position);
            holder.bind(data);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            CardView card;
            TextView tv;
            LinearLayout buttonsLayout;
            android.widget.Button btnCancel, btnPay, btnPartial, btnMove;

            ViewHolder(@NonNull CardView card) {
                super(card);
                this.card = card;
                // Δημιουργούμε το περιεχόμενο της κάρτας (ίδιο όπως στην createTableCard)
                LinearLayout cardContent = new LinearLayout(ActiveTablesActivity.this);
                cardContent.setOrientation(LinearLayout.VERTICAL);
                tv = new TextView(ActiveTablesActivity.this);
                tv.setTextSize(18f);
                tv.setTextColor(Color.BLACK);
                cardContent.addView(tv);

                buttonsLayout = new LinearLayout(ActiveTablesActivity.this);
                buttonsLayout.setOrientation(LinearLayout.VERTICAL);
                buttonsLayout.setPadding(0, 24, 0, 0);
                cardContent.addView(buttonsLayout);
                card.addView(cardContent);
            }

            void bind(TableCardData data) {
                // Ενημερώνουμε το TextView
                String displayText = "Τραπέζι " + data.tableNumber + "\n\n" + data.details;
                tv.setText(displayText);

                // Χρώμα φόντου
                if (data.isEmpty) {
                    card.setCardBackgroundColor(Color.LTGRAY);
                } else if ("ordered".equals(data.status)) {
                    card.setCardBackgroundColor(Color.parseColor("#FFB74D"));
                } else {
                    card.setCardBackgroundColor(Color.WHITE);
                }

                // Αφαιρούμε τα παλιά κουμπιά και ξαναχτίζουμε ανάλογα με την κατάσταση
                buttonsLayout.removeAllViews();
                if (!data.isEmpty) {
                    if ("ordered".equals(data.status)) {
                        buildOrderedButtons(data);
                    } else {
                        buildNormalButtons(data);
                    }
                }
            }

            private void buildNormalButtons(TableCardData data) {
                // Πρώτη γραμμή: ΑΚΥΡΩΣΗ | ΕΚΔΟΣΗ ΑΠΟΔΕΙΞΗΣ
                LinearLayout row1 = new LinearLayout(ActiveTablesActivity.this);
                row1.setOrientation(LinearLayout.HORIZONTAL);
                btnCancel = new android.widget.Button(ActiveTablesActivity.this);
                btnCancel.setText("ΑΚΥΡΩΣΗ");
                btnCancel.setBackgroundColor(Color.parseColor("#F44336"));
                btnCancel.setTextColor(Color.WHITE);
                LinearLayout.LayoutParams btnCancelParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                btnCancelParams.setMargins(0, 0, 8, 0);
                btnCancel.setLayoutParams(btnCancelParams);
                btnCancel.setOnClickListener(v -> {
                    billsRef.child(data.tableNumber).removeValue()
                            .addOnSuccessListener(aVoid -> Toast.makeText(ActiveTablesActivity.this, "Το τραπέζι " + data.tableNumber + " ακυρώθηκε", Toast.LENGTH_SHORT).show());
                    saveToHistory(HistoryEntry.TYPE_TABLE_CANCELLED, data.tableNumber, 0.0, null, "Ακύρωση τραπεζιού (swipe)");
                });

                btnPay = new android.widget.Button(ActiveTablesActivity.this);
                btnPay.setText("ΕΚΔΟΣΗ ΑΠΟΔΕΙΞΗΣ");
                btnPay.setBackgroundColor(Color.parseColor("#4CAF50"));
                btnPay.setTextColor(Color.WHITE);
                btnPay.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                btnPay.setOnClickListener(v -> initiatePayment(data));

                row1.addView(btnCancel);
                row1.addView(btnPay);
                buttonsLayout.addView(row1);

                // Δεύτερη γραμμή: ΜΕΡΙΚΗ ΕΞΟΦΛΗΣΗ
                btnPartial = new android.widget.Button(ActiveTablesActivity.this);
                btnPartial.setText("ΜΕΡΙΚΗ ΕΞΟΦΛΗΣΗ");
                btnPartial.setBackgroundColor(Color.parseColor("#FF9800"));
                btnPartial.setTextColor(Color.WHITE);
                LinearLayout.LayoutParams btnPartialParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                btnPartialParams.setMargins(0, 8, 0, 0);
                btnPartial.setLayoutParams(btnPartialParams);
                btnPartial.setOnClickListener(v -> initiatePartialPayment(data));
                buttonsLayout.addView(btnPartial);

                // Τρίτη γραμμή: ΜΕΤΑΚΙΝΗΣΗ
                btnMove = new android.widget.Button(ActiveTablesActivity.this);
                btnMove.setText("ΜΕΤΑΚΙΝΗΣΗ");
                btnMove.setBackgroundColor(Color.parseColor("#2196F3"));
                btnMove.setTextColor(Color.WHITE);
                LinearLayout.LayoutParams btnMoveParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                btnMoveParams.setMargins(0, 8, 0, 0);
                btnMove.setLayoutParams(btnMoveParams);
                btnMove.setOnClickListener(v -> {
                    if (isMergeMode) {
                        Toast.makeText(ActiveTablesActivity.this, "Βγείτε πρώτα από την κατάσταση συγχώνευσης", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!isMoveMode) {
                        isMoveMode = true;
                        showAllTablesForMove = true;
                        moveSourceTable = null;
                        if (moveSourceCard != null) {
                            moveSourceCard.setCardBackgroundColor(Color.WHITE);
                            moveSourceCard = null;
                        }
                        loadActiveTables();
                        Toast.makeText(ActiveTablesActivity.this, "Επιλέξτε το τραπέζι ΠΗΓΗ προς μετακίνηση", Toast.LENGTH_SHORT).show();
                    } else {
                        isMoveMode = false;
                        showAllTablesForMove = false;
                        moveSourceTable = null;
                        if (moveSourceCard != null) {
                            moveSourceCard.setCardBackgroundColor(Color.WHITE);
                            moveSourceCard = null;
                        }
                        loadActiveTables();
                        Toast.makeText(ActiveTablesActivity.this, "Μετακίνηση ακυρώθηκε", Toast.LENGTH_SHORT).show();
                    }
                });
                buttonsLayout.addView(btnMove);
            }

            private void buildOrderedButtons(TableCardData data) {
                // Πρώτη γραμμή: ΕΚΤΥΠΩΣΗ ΠΡΟΣΩΡΙΝΗΣ
                android.widget.Button btnPrintTemp = new android.widget.Button(ActiveTablesActivity.this);
                btnPrintTemp.setText("ΕΚΤΥΠΩΣΗ ΠΡΟΣΩΡΙΝΗΣ");
                btnPrintTemp.setBackgroundColor(Color.parseColor("#2196F3"));
                btnPrintTemp.setTextColor(Color.WHITE);
                LinearLayout.LayoutParams btnPrintParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                btnPrintParams.setMargins(0, 0, 0, 0);
                btnPrintTemp.setLayoutParams(btnPrintParams);
                btnPrintTemp.setOnClickListener(v -> {
                    Map<String, Object> update = new HashMap<>();
                    update.put("status", "printed");
                    billsRef.child(data.tableNumber).child("current_order").updateChildren(update)
                            .addOnSuccessListener(aVoid -> {
                                sendTempReceiptToPrinter(data.tableNumber, data.tableData);
                                Toast.makeText(ActiveTablesActivity.this, "Η προσωρινή απόδειξη εστάλη για εκτύπωση", Toast.LENGTH_SHORT).show();
                                loadActiveTables();
                            });
                });
                buttonsLayout.addView(btnPrintTemp);

                // Δεύτερη γραμμή: ΑΚΥΡΩΣΗ | ΜΕΤΑΚΙΝΗΣΗ
                LinearLayout rowOrdered = new LinearLayout(ActiveTablesActivity.this);
                rowOrdered.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.setMargins(0, 8, 0, 0);
                rowOrdered.setLayoutParams(rowParams);

                android.widget.Button btnCancelOrdered = new android.widget.Button(ActiveTablesActivity.this);
                btnCancelOrdered.setText("ΑΚΥΡΩΣΗ");
                btnCancelOrdered.setBackgroundColor(Color.parseColor("#F44336"));
                btnCancelOrdered.setTextColor(Color.WHITE);
                LinearLayout.LayoutParams btnCancelParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                btnCancelParams.setMargins(0, 0, 8, 0);
                btnCancelOrdered.setLayoutParams(btnCancelParams);
                btnCancelOrdered.setOnClickListener(v -> {
                    billsRef.child(data.tableNumber).removeValue()
                            .addOnSuccessListener(aVoid -> {
                                saveToHistory(HistoryEntry.TYPE_TABLE_CANCELLED, data.tableNumber, 0.0, null, "Ακύρωση τραπεζιού");
                                Toast.makeText(ActiveTablesActivity.this, "Το τραπέζι " + data.tableNumber + " ακυρώθηκε", Toast.LENGTH_SHORT).show();
                            });
                });

                android.widget.Button btnMoveOrdered = new android.widget.Button(ActiveTablesActivity.this);
                btnMoveOrdered.setText("ΜΕΤΑΚΙΝΗΣΗ");
                btnMoveOrdered.setBackgroundColor(Color.parseColor("#2196F3"));
                btnMoveOrdered.setTextColor(Color.WHITE);
                btnMoveOrdered.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                btnMoveOrdered.setOnClickListener(v -> {
                    if (isMergeMode) {
                        Toast.makeText(ActiveTablesActivity.this, "Βγείτε πρώτα από την κατάσταση συγχώνευσης", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!isMoveMode) {
                        isMoveMode = true;
                        showAllTablesForMove = true;
                        moveSourceTable = null;
                        if (moveSourceCard != null) {
                            moveSourceCard.setCardBackgroundColor(Color.WHITE);
                            moveSourceCard = null;
                        }
                        loadActiveTables();
                        Toast.makeText(ActiveTablesActivity.this, "Επιλέξτε το τραπέζι ΠΗΓΗ προς μετακίνηση", Toast.LENGTH_SHORT).show();
                    } else {
                        isMoveMode = false;
                        showAllTablesForMove = false;
                        moveSourceTable = null;
                        if (moveSourceCard != null) {
                            moveSourceCard.setCardBackgroundColor(Color.WHITE);
                            moveSourceCard = null;
                        }
                        loadActiveTables();
                        Toast.makeText(ActiveTablesActivity.this, "Μετακίνηση ακυρώθηκε", Toast.LENGTH_SHORT).show();
                    }
                });

                rowOrdered.addView(btnCancelOrdered);
                rowOrdered.addView(btnMoveOrdered);
                buttonsLayout.addView(rowOrdered);
            }

            private void initiatePayment(TableCardData data) {
                // Μεταφορά της λογικής από τον btnPay.setOnClickListener
                pendingTableData = data.tableData;
                pendingTableNumber = data.tableNumber;
                pendingOrderDetails = data.details;
                double totalAmount = calculateTotalAmount(data.tableData);
                pendingAmount = totalAmount;

                new AlertDialog.Builder(ActiveTablesActivity.this)
                        .setTitle("Διαχωρισμός Λογαριασμού;")
                        .setMessage("Το συνολικό ποσό είναι €" + String.format("%.2f", totalAmount) + "\n\nΘέλετε να διαχωρίσετε τον λογαριασμό;")
                        .setPositiveButton("Ναι, διαχωρισμός", (dialog, which) -> {
                            Intent intent = new Intent(ActiveTablesActivity.this, SplitItemsActivity.class);
                            intent.putExtra("table_number", data.tableNumber);
                            intent.putExtra("table_data_json", new Gson().toJson(data.tableData));
                            startActivityForResult(intent, REQUEST_SPLIT_ITEMS);
                        })
                        .setNegativeButton("Όχι, ολόκληρο", (dialog, which) -> {
                            showPaymentMethodDialog("Ολόκληρο το ποσό (€" + String.format("%.2f", totalAmount) + ")",
                                    () -> {
                                        billsRef.child(data.tableNumber).removeValue();
                                        Toast.makeText(ActiveTablesActivity.this, "Ο λογαριασμός εξοφλήθηκε!", Toast.LENGTH_SHORT).show();
                                    });
                        })
                        .show();
            }

            private void initiatePartialPayment(TableCardData data) {
                pendingTableData = data.tableData;
                pendingTableNumber = data.tableNumber;
                double totalAmount = calculateTotalAmount(data.tableData);
                Intent intent = new Intent(ActiveTablesActivity.this, SplitItemsActivity.class);
                intent.putExtra("table_number", data.tableNumber);
                intent.putExtra("table_data_json", new Gson().toJson(data.tableData));
                intent.putExtra("is_partial", true);
                startActivityForResult(intent, REQUEST_SPLIT_ITEMS_PARTIAL);
            }
        }

        // Μοντέλο δεδομένων για την κάρτα
        private class TableCardData {
            String tableNumber;
            String details;
            Map<String, Object> tableData;
            String status;
            boolean isEmpty;

            TableCardData(String tableNumber, String details, Map<String, Object> tableData, String status, boolean isEmpty) {
                this.tableNumber = tableNumber;
                this.details = details;
                this.tableData = tableData;
                this.status = status;
                this.isEmpty = isEmpty;
            }
        }
}}