package com.ads.paragelia;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class NewOrderActivity extends AppCompatActivity {

    private RecyclerView tableRecyclerView;
    private TableAdapter tableAdapter;
    private DatabaseReference activeBillsRef;
    private List<TableData> tableList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_order);

        tableRecyclerView = findViewById(R.id.tableRecyclerView);
        tableRecyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        tableAdapter = new TableAdapter();
        tableRecyclerView.setAdapter(tableAdapter);

        activeBillsRef = FirebaseDatabase.getInstance().getReference("active_bills");

        // Αρχικοποίηση λίστας για τραπέζια 1-10
        for (int i = 1; i <= 10; i++) {
            tableList.add(new TableData(String.valueOf(i), "Καμία παραγγελία", false));
        }
        tableAdapter.notifyDataSetChanged();



        // Live ενημέρωση από Firebase
        activeBillsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (int i = 1; i <= 10; i++) {
                    String tableKey = String.valueOf(i);
                    if (snapshot.hasChild(tableKey)) {
                        Map<String, Object> tableData = (Map<String, Object>) snapshot.child(tableKey).getValue();
                        String summary = buildOrderSummary(tableData);
                        tableList.get(i-1).setSummary(summary);
                        tableList.get(i-1).setHasOrder(true);
                    } else {
                        tableList.get(i-1).setSummary("Καμία παραγγελία");
                        tableList.get(i-1).setHasOrder(false);
                    }
                }
                tableAdapter.notifyDataSetChanged();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) { }
        });
    }

    private String buildOrderSummary(Map<String, Object> tableData) {
        StringBuilder sb = new StringBuilder();
        int totalItems = 0;
        for (Map.Entry<String, Object> entry : tableData.entrySet()) {
            if (entry.getValue() instanceof Map) {
                Map<String, Object> order = (Map<String, Object>) entry.getValue();
                Object itemsObj = order.get("items");
                if (itemsObj instanceof List) {
                    List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
                    for (Map<String, Object> item : items) {
                        if (totalItems >= 5) {
                            sb.append("...");
                            break;
                        }
                        String name = (String) item.get("name");
                        Object qtyObj = item.get("quantity");
                        int qty = (qtyObj instanceof Long) ? ((Long) qtyObj).intValue() : (int) qtyObj;
                        sb.append(name).append(" x").append(qty).append(", ");
                        totalItems++;
                    }
                }
            }
        }
        if (sb.length() > 2) {
            sb.setLength(sb.length() - 2);
            return sb.toString();
        }
        return "Καμία παραγγελία";
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
            holder.tvTableNumber.setText("Τραπέζι " + data.tableNumber);
            holder.tvOrderSummary.setText(data.summary);
            if (data.hasOrder) {
                holder.cardView.setCardBackgroundColor(0xFFE8F5E9);
            } else {
                holder.cardView.setCardBackgroundColor(0xFFFFFFFF);
            }

            // Άνοιγμα TableOrderActivity όταν πατηθεί ΟΛΟΚΛΗΡΗ η κάρτα
            holder.cardView.setOnClickListener(v -> {
                Intent intent = new Intent(NewOrderActivity.this, TableOrderActivity.class);
                intent.putExtra("table_number", data.tableNumber);
                startActivity(intent);
            });

            // (Προαιρετικά) Μπορείς να αφήσεις το κουμπί "+ Προσθήκη" να κάνει το ίδιο
            holder.btnAddExtra.setOnClickListener(v -> {
                Intent intent = new Intent(NewOrderActivity.this, TableOrderActivity.class);
                intent.putExtra("table_number", data.tableNumber);
                startActivity(intent);
            });
        }

        @Override
        public int getItemCount() { return tableList.size(); }

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

    private static class TableData {
        String tableNumber; String summary; boolean hasOrder;
        TableData(String tableNumber, String summary, boolean hasOrder) {
            this.tableNumber = tableNumber; this.summary = summary; this.hasOrder = hasOrder;
        }
        void setSummary(String summary) { this.summary = summary; }
        void setHasOrder(boolean hasOrder) { this.hasOrder = hasOrder; }
    }
}