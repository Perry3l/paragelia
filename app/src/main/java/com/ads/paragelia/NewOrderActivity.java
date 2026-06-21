package com.ads.paragelia;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NewOrderActivity extends BaseActivity {

    private static final String TAG = "NewOrderActivity";
    private RecyclerView tableRecyclerView;
    private TableAdapter tableAdapter;
    private DatabaseReference activeBillsRef;
    private List<TableData> tableList = new ArrayList<>();
    private Button btnMergeTables;
    private Button btnUnmergeTables;
    private boolean isMergeMode = false;
    private boolean isUnmergeMode = false;
    private String sourceTable = null;
    private DatabaseReference billsRef;
    private boolean orderOnlyMode = false;
    private SystemSettingsManager settingsManager;
    private Runnable settingsListener;

    private int getMaxTables() {
        SharedPreferences prefs = getSharedPreferences("system_prefs", MODE_PRIVATE);
        return prefs.getInt("max_tables", 10);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_order);
        showMemoryOverlay();

        settingsManager = SystemSettingsManager.getInstance();
        settingsListener = () -> {
            tableList.clear();
            for (int i = 1; i <= settingsManager.getMaxTables(); i++) {
                tableList.add(new TableData(String.valueOf(i)));
            }
            tableAdapter.notifyDataSetChanged();
        };
        settingsManager.addListener(settingsListener);

        SharedPreferences orderPrefs = getSharedPreferences(SettingsActivity.PREFS_ORDER_MODE, MODE_PRIVATE);
        orderOnlyMode = orderPrefs.getBoolean(SettingsActivity.KEY_ORDER_ONLY_MODE, false);

        tableRecyclerView = findViewById(R.id.tableRecyclerView);
        tableRecyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        tableAdapter = new TableAdapter();
        tableRecyclerView.setAdapter(tableAdapter);

        activeBillsRef = FirebaseHelper.getReference("active_bills");
        billsRef = activeBillsRef;

        for (int i = 1; i <= settingsManager.getMaxTables(); i++) {
            tableList.add(new TableData(String.valueOf(i)));
        }
        tableAdapter.notifyDataSetChanged();

        activeBillsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Log.d(TAG, "🔥 onDataChange: snapshot has " + snapshot.getChildrenCount() + " children");
                for (int i = 1; i <= settingsManager.getMaxTables(); i++) {
                    String tableKey = String.valueOf(i);
                    TableData data = tableList.get(i - 1);
                    data.clearMerged();
                    data.setHasOrder(false);
                    data.setSummary("Καμία παραγγελία");
                    data.setStatus("pending");
                    data.totalAmount = 0.0;

                    if (snapshot.hasChild(tableKey)) {
                        DataSnapshot tableSnap = snapshot.child(tableKey);
                        Map<String, Object> tableData = (Map<String, Object>) tableSnap.getValue();
                        Log.d(TAG, "📋 Table " + tableKey + " data: " + tableData);

                        if (tableData != null && tableData.containsKey("merged_to")) {
                            String mergedTo = (String) tableData.get("merged_to");
                            data.setMerged(mergedTo);
                            continue;
                        }

                        boolean foundItems = false;
                        double total = 0.0;
                        StringBuilder itemsText = new StringBuilder();
                        int itemCount = 0;

                        for (DataSnapshot child : tableSnap.getChildren()) {
                            Map<String, Object> order = (Map<String, Object>) child.getValue();
                            if (order != null && order.containsKey("items")) {
                                Object itemsObj = order.get("items");
                                if (itemsObj instanceof List) {
                                    List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
                                    for (Map<String, Object> item : items) {
                                        if (itemCount >= 5) {
                                            itemsText.append("...");
                                            break;
                                        }
                                        String name = (String) item.get("name");
                                        int qty = ((Number) item.get("quantity")).intValue();
                                        double price = ((Number) item.get("price")).doubleValue();
                                        total += price * qty;
                                        itemsText.append(name).append(" x").append(qty).append(", ");
                                        itemCount++;
                                        foundItems = true;
                                    }
                                }
                            }
                        }

                        if (foundItems) {
                            if (itemsText.length() > 2) itemsText.setLength(itemsText.length() - 2);
                            data.setSummary(itemsText.toString());
                            data.setHasOrder(true);
                            data.totalAmount = total;
                        }

                        if (tableData != null && tableData.containsKey("current_order")) {
                            Map<String, Object> cur = (Map<String, Object>) tableData.get("current_order");
                            if (cur != null && cur.containsKey("status")) {
                                data.setStatus((String) cur.get("status"));
                            }
                            if (cur != null && cur.containsKey("merged_from")) {
                                data.setMergedFrom((String) cur.get("merged_from"));
                            }
                        }
                    }
                }
                tableAdapter.notifyDataSetChanged();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Σφάλμα ανάγνωσης: " + error.getMessage());
            }
        });

        btnMergeTables = findViewById(R.id.btnMergeTables);
        btnUnmergeTables = findViewById(R.id.btnUnmergeTables);

        btnMergeTables.setOnClickListener(v -> {
            if (isUnmergeMode) {
                Toast.makeText(this, "Βγείτε πρώτα από τη λειτουργία διάσπασης", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isMergeMode) {
                isMergeMode = true;
                sourceTable = null;
                btnMergeTables.setText("Ακύρωση Συγχώνευσης");
                btnUnmergeTables.setVisibility(View.GONE);
                tableAdapter.notifyDataSetChanged();
                Toast.makeText(this, "Επιλέξτε το τραπέζι ΠΗΓΗ", Toast.LENGTH_SHORT).show();
            } else {
                isMergeMode = false;
                sourceTable = null;
                btnMergeTables.setText("Συγχώνευση");
                btnUnmergeTables.setVisibility(View.GONE);
                tableAdapter.notifyDataSetChanged();
                Toast.makeText(this, "Συγχώνευση ακυρώθηκε", Toast.LENGTH_SHORT).show();
            }
        });

        btnUnmergeTables.setOnClickListener(v -> {
            if (isMergeMode) {
                Toast.makeText(this, "Βγείτε πρώτα από τη λειτουργία συγχώνευσης", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isUnmergeMode) {
                isUnmergeMode = true;
                btnUnmergeTables.setText("Ακύρωση Διάσπασης");
                btnMergeTables.setVisibility(View.GONE);
                tableAdapter.notifyDataSetChanged();
                Toast.makeText(this, "Επιλέξτε το τραπέζι που θέλετε να διασπάσετε", Toast.LENGTH_SHORT).show();
            } else {
                isUnmergeMode = false;
                btnUnmergeTables.setText("Διάσπαση");
                btnMergeTables.setVisibility(View.VISIBLE);
                tableAdapter.notifyDataSetChanged();
                Toast.makeText(this, "Διάσπαση ακυρώθηκε", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String buildSummaryFromItems(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) return "Καμία παραγγελία";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map<String, Object> item : items) {
            if (count >= 5) {
                sb.append("...");
                break;
            }
            String name = (String) item.get("name");
            Object qtyObj = item.get("quantity");
            int qty = (qtyObj instanceof Number) ? ((Number) qtyObj).intValue() : 1;
            sb.append(name).append(" x").append(qty).append(", ");
            count++;
        }
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private class TableAdapter extends RecyclerView.Adapter<TableAdapter.TableViewHolder> {

        @NonNull @Override
        public TableViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_table_card, parent, false);
            return new TableViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull TableViewHolder holder, int position) {
            TableData data = tableList.get(position);

            if (data.isMerged()) {
                holder.tvTableNumber.setText("Τραπέζι " + data.tableNumber + " 🔀");
                holder.tvOrderSummary.setText("Συγχωνεύτηκε με " + data.mergedTo);
                holder.cardView.setCardBackgroundColor(Color.LTGRAY);
                holder.btnAddExtra.setVisibility(View.GONE);
                holder.cardView.setOnClickListener(v -> {
                    String destTable = data.mergedTo;
                    ProductSelectionBottomSheet bottomSheet = ProductSelectionBottomSheet.newInstance(destTable);
                    bottomSheet.show(getSupportFragmentManager(), "product_sheet");
                });
                holder.cardView.setAlpha(1.0f);
                return;
            }

            holder.tvTableNumber.setText("Τραπέζι " + data.tableNumber);
            holder.tvOrderSummary.setText(data.summary);
            holder.btnAddExtra.setVisibility(View.VISIBLE);
            holder.cardView.setAlpha(1.0f);

            if (data.tableNumber.equals(sourceTable) && isMergeMode) {
                holder.cardView.setCardBackgroundColor(0xFFFFD54F);
            } else if (data.hasOrder) {
                if ("ordered".equals(data.status)) {
                    holder.cardView.setCardBackgroundColor(0xFFFFB74D);
                } else {
                    holder.cardView.setCardBackgroundColor(0xFFE8F5E9);
                }
            } else {
                holder.cardView.setCardBackgroundColor(0xFFFFFFFF);
            }

            holder.cardView.setOnClickListener(v -> {
                TableData selectedData = tableList.get(holder.getAdapterPosition());

                if (isUnmergeMode) {
                    if (!selectedData.hasOrder || selectedData.mergedFrom == null) {
                        Toast.makeText(v.getContext(), "Το τραπέζι δεν είναι συγχωνευμένο", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    unmergeTable(selectedData.tableNumber);
                    return;
                }

                if (isMergeMode) {
                    if (selectedData.isMerged()) {
                        Toast.makeText(v.getContext(), "Το τραπέζι έχει ήδη συγχωνευτεί", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (sourceTable == null) {
                        sourceTable = selectedData.tableNumber;
                        tableAdapter.notifyDataSetChanged();
                        String msg = selectedData.hasOrder ?
                                "Τραπέζι " + sourceTable + " ορίστηκε ως ΠΗΓΗ. Επιλέξτε ΠΡΟΟΡΙΣΜΟ." :
                                "Κενό τραπέζι " + sourceTable + " ως ΠΗΓΗ. Επιλέξτε ένα ακόμη τραπέζι για ένωση.";
                        Toast.makeText(v.getContext(), msg, Toast.LENGTH_SHORT).show();
                    } else {
                        if (sourceTable.equals(selectedData.tableNumber)) {
                            Toast.makeText(v.getContext(), "Δεν μπορείτε να συγχωνεύσετε το ίδιο τραπέζι", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        TableData sourceData = tableList.get(getIndexByTableNumber(sourceTable));
                        if (!sourceData.hasOrder && !selectedData.hasOrder) {
                            performMergeEmptyTables(sourceTable, selectedData.tableNumber);
                            return;
                        }
                        performMerge(sourceTable, selectedData.tableNumber);
                    }
                } else {
                    if (selectedData.isMerged()) {
                        Toast.makeText(v.getContext(), "Το τραπέζι έχει συγχωνευτεί", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ProductSelectionBottomSheet bottomSheet = ProductSelectionBottomSheet.newInstance(selectedData.tableNumber);
                    bottomSheet.show(getSupportFragmentManager(), "product_sheet");
                }
            });

            holder.btnAddExtra.setOnClickListener(v -> {
                if (isMergeMode || isUnmergeMode) {
                    Toast.makeText(v.getContext(), "Δεν μπορείτε να προσθέσετε προϊόντα αυτή τη στιγμή", Toast.LENGTH_SHORT).show();
                    return;
                }
                ProductSelectionBottomSheet bottomSheet = ProductSelectionBottomSheet.newInstance(data.tableNumber);
                bottomSheet.show(getSupportFragmentManager(), "product_sheet");
            });
        }

        @Override
        public int getItemCount() {
            return tableList.size();
        }

        class TableViewHolder extends RecyclerView.ViewHolder {
            TextView tvTableNumber, tvOrderSummary;
            Button btnAddExtra;
            CardView cardView;

            TableViewHolder(@NonNull View itemView) {
                super(itemView);
                tvTableNumber = itemView.findViewById(R.id.tvTableNumber);
                tvOrderSummary = itemView.findViewById(R.id.tvOrderSummary);
                btnAddExtra = itemView.findViewById(R.id.btnAddExtra);
                cardView = (CardView) itemView;
            }
        }
    }

    private void unmergeTable(String tableNumber) {
        DatabaseReference tableRef = billsRef.child(tableNumber);
        tableRef.child("current_order").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    Toast.makeText(NewOrderActivity.this, "Το τραπέζι δεν έχει παραγγελία", Toast.LENGTH_SHORT).show();
                    resetUnmergeMode();
                    return;
                }
                Map<String, Object> curOrder = (Map<String, Object>) snapshot.getValue();
                if (curOrder == null || !curOrder.containsKey("merged_from")) {
                    Toast.makeText(NewOrderActivity.this, "Το τραπέζι δεν είναι συγχωνευμένο", Toast.LENGTH_SHORT).show();
                    resetUnmergeMode();
                    return;
                }
                String mergedFrom = (String) curOrder.get("merged_from");
                tableRef.child("current_order").child("merged_from").removeValue()
                        .addOnSuccessListener(aVoid -> {
                            DatabaseReference sourceRef = billsRef.child(mergedFrom);
                            sourceRef.removeValue()
                                    .addOnSuccessListener(aVoid2 -> {
                                        saveToHistory(HistoryEntry.TYPE_TABLE_MOVED,
                                                tableNumber + " διασπάστηκε από " + mergedFrom, 0.0, null,
                                                "Διάσπαση τραπεζιών");
                                        Toast.makeText(NewOrderActivity.this,
                                                "Το τραπέζι " + tableNumber + " διασπάστηκε από το " + mergedFrom,
                                                Toast.LENGTH_SHORT).show();
                                        resetUnmergeMode();
                                    })
                                    .addOnFailureListener(e -> {
                                        Toast.makeText(NewOrderActivity.this, "Σφάλμα καθαρισμού πηγής", Toast.LENGTH_SHORT).show();
                                        resetUnmergeMode();
                                    });
                        })
                        .addOnFailureListener(e -> {
                            Toast.makeText(NewOrderActivity.this, "Σφάλμα ενημέρωσης τραπεζιού", Toast.LENGTH_SHORT).show();
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
        tableAdapter.notifyDataSetChanged();
    }

    private void performMerge(String source, String destination) {
        DatabaseReference sourceRef = billsRef.child(source);
        DatabaseReference destRef = billsRef.child(destination);

        sourceRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot sourceSnap) {
                if (!sourceSnap.exists()) {
                    performMergeEmptyTables(source, destination);
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
                                                Toast.makeText(NewOrderActivity.this,
                                                        "Συγχώνευση ολοκληρώθηκε: " + source + " + " + destination,
                                                        Toast.LENGTH_SHORT).show();
                                                resetMergeMode();
                                            })
                                            .addOnFailureListener(e -> {
                                                Toast.makeText(NewOrderActivity.this, "Σφάλμα μαρκαρίσματος πηγής", Toast.LENGTH_SHORT).show();
                                                resetMergeMode();
                                            });
                                })
                                .addOnFailureListener(e -> {
                                    Toast.makeText(NewOrderActivity.this, "Σφάλμα αποθήκευσης προορισμού", Toast.LENGTH_SHORT).show();
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

    private void performMergeEmptyTables(String source, String destination) {
        DatabaseReference sourceRef = billsRef.child(source);
        Map<String, Object> mergedFlag = new HashMap<>();
        mergedFlag.put("merged_to", destination);
        sourceRef.setValue(mergedFlag)
                .addOnSuccessListener(aVoid -> {
                    saveToHistory(HistoryEntry.TYPE_TABLE_MERGED,
                            source + " → " + destination, 0.0, null,
                            "Ένωση δύο κενών τραπεζιών");
                    Toast.makeText(NewOrderActivity.this,
                            "Τα τραπέζια " + source + " και " + destination + " ενώθηκαν!",
                            Toast.LENGTH_SHORT).show();
                    resetMergeMode();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(NewOrderActivity.this, "Σφάλμα", Toast.LENGTH_SHORT).show();
                    resetMergeMode();
                });
    }

    private void addAllItemsFromTableData(Map<String, Object> tableData, List<Map<String, Object>> targetList) {
        if (tableData == null) return;
        for (Map.Entry<String, Object> entry : tableData.entrySet()) {
            if (!(entry.getValue() instanceof Map)) continue;
            Map<String, Object> order = (Map<String, Object>) entry.getValue();
            Object itemsObj = order.get("items");
            if (itemsObj instanceof List) {
                targetList.addAll((List<Map<String, Object>>) itemsObj);
            }
        }
    }

    private int getIndexByTableNumber(String tableNumber) {
        for (int i = 0; i < tableList.size(); i++) {
            if (tableList.get(i).tableNumber.equals(tableNumber)) return i;
        }
        return -1;
    }

    private void resetMergeMode() {
        isMergeMode = false;
        sourceTable = null;
        btnMergeTables.setText("Συγχώνευση");
        btnUnmergeTables.setVisibility(View.VISIBLE);
        tableAdapter.notifyDataSetChanged();
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

    private static class TableData {
        String tableNumber;
        String summary = "Καμία παραγγελία";
        boolean hasOrder = false;
        boolean merged = false;
        String mergedTo = "";
        String mergedFrom = null;
        String status = "pending";
        double totalAmount = 0.0;

        TableData(String tableNumber) {
            this.tableNumber = tableNumber;
        }

        void setSummary(String summary) { this.summary = summary; }
        void setHasOrder(boolean hasOrder) { this.hasOrder = hasOrder; }
        void setMerged(String to) { this.merged = true; this.mergedTo = to; this.mergedFrom = null; }
        void setMergedFrom(String from) { this.mergedFrom = from; }
        void clearMerged() { this.merged = false; this.mergedTo = ""; this.mergedFrom = null; }
        boolean isMerged() { return merged; }
        void setStatus(String status) { this.status = status; }
    }
}