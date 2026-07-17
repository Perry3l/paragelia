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

    private void openQuickOrder(String tableNumber) {
        android.content.Intent intent = new android.content.Intent(this, QuickOrderActivity.class);
        intent.putExtra(QuickOrderActivity.EXTRA_TABLE_NUMBER, tableNumber);
        startActivity(intent);
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
                    openQuickOrder(destTable);
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
                        performMerge(sourceTable, selectedData.tableNumber);
                    }
                } else {
                    if (selectedData.isMerged()) {
                        Toast.makeText(v.getContext(), "Το τραπέζι έχει συγχωνευτεί", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    openQuickOrder(selectedData.tableNumber);
                }
            });

            holder.btnAddExtra.setOnClickListener(v -> {
                if (isMergeMode || isUnmergeMode) {
                    Toast.makeText(v.getContext(), "Δεν μπορείτε να προσθέσετε προϊόντα αυτή τη στιγμή", Toast.LENGTH_SHORT).show();
                    return;
                }
                openQuickOrder(data.tableNumber);
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
        TableMergeHelper.unmerge(tableNumber, new TableMergeHelper.Callback() {
            @Override
            public void onSuccess(String message) {
                saveToHistory(HistoryEntry.TYPE_TABLE_MOVED,
                        "Διάσπαση " + tableNumber, 0.0, null, "Διάσπαση τραπεζιών");
                Toast.makeText(NewOrderActivity.this, message, Toast.LENGTH_SHORT).show();
                resetUnmergeMode();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(NewOrderActivity.this, message, Toast.LENGTH_SHORT).show();
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
        TableMergeHelper.merge(source, destination, new TableMergeHelper.Callback() {
            @Override
            public void onSuccess(String message) {
                saveToHistory(HistoryEntry.TYPE_TABLE_MERGED,
                        source + " → " + destination, 0.0, null, "Συγχώνευση τραπεζιών");
                Toast.makeText(NewOrderActivity.this, message, Toast.LENGTH_SHORT).show();
                resetMergeMode();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(NewOrderActivity.this, message, Toast.LENGTH_LONG).show();
                resetMergeMode();
            }
        });
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