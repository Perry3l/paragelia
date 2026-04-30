package com.ads.paragelia;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends BaseActivity {

    private RecyclerView rvHistory;
    private HistoryAdapter adapter;
    private List<HistoryEntry> historyList = new ArrayList<>();
    private DatabaseReference historyRef;
    private Button btnClearView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        rvHistory = findViewById(R.id.rvHistory);
        btnClearView = findViewById(R.id.btnClearView);

        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HistoryAdapter(historyList);
        rvHistory.setAdapter(adapter);

        historyRef = FirebaseDatabase.getInstance().getReference("history");

        loadHistory();

        btnClearView.setOnClickListener(v -> {
            historyList.clear();
            adapter.notifyDataSetChanged();
            btnClearView.setVisibility(View.GONE);
            Toast.makeText(this, "Η προβολή καθαρίστηκε προσωρινά", Toast.LENGTH_SHORT).show();
        });
    }

    private void loadHistory() {
        historyRef.orderByChild("timestamp").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                historyList.clear();
                for (DataSnapshot snap : snapshot.getChildren()) {
                    HistoryEntry entry = snap.getValue(HistoryEntry.class);
                    if (entry != null) {
                        // Αγνόηση ακυρώσεων
                        if (HistoryEntry.TYPE_TABLE_CANCELLED.equals(entry.type) ||
                                HistoryEntry.TYPE_ORDER_COMPLETED.equals(entry.type)) {
                            continue;
                        }
                        historyList.add(entry);
                    }
                }
                Collections.reverse(historyList);
                adapter.notifyDataSetChanged();

                // Επαναφορά του κουμπιού αν υπάρχουν δεδομένα
                if (!historyList.isEmpty()) {
                    btnClearView.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(HistoryActivity.this, "Σφάλμα φόρτωσης ιστορικού", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {
        private List<HistoryEntry> list;

        HistoryAdapter(List<HistoryEntry> list) {
            this.list = list;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_history, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            HistoryEntry entry = list.get(position);
            holder.tvType.setText(getTypeDisplayName(entry.type));
            holder.tvTable.setText("Τραπέζι: " + entry.tableNumber);
            if (entry.amount > 0) {
                holder.tvAmount.setText("Ποσό: €" + String.format("%.2f", entry.amount));
                holder.tvAmount.setVisibility(View.VISIBLE);
            } else {
                holder.tvAmount.setVisibility(View.GONE);
            }
            holder.tvDevice.setText("Συσκευή: " + entry.deviceName);
            holder.tvTime.setText("Ώρα: " + formatTimestamp(entry.timestamp));
            holder.tvDetails.setText(entry.details != null ? entry.details : "");
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvType, tvTable, tvAmount, tvDevice, tvTime, tvDetails;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvType = itemView.findViewById(R.id.tvHistoryType);
                tvTable = itemView.findViewById(R.id.tvHistoryTable);
                tvAmount = itemView.findViewById(R.id.tvHistoryAmount);
                tvDevice = itemView.findViewById(R.id.tvHistoryDevice);
                tvTime = itemView.findViewById(R.id.tvHistoryTime);
                tvDetails = itemView.findViewById(R.id.tvHistoryDetails);
            }
        }
    }

    private String getTypeDisplayName(String type) {
        if (type == null) return "Άγνωστο";
        switch (type) {
            case HistoryEntry.TYPE_ORDER_COMPLETED: return "✅ Ολοκλήρωση Παραγγελίας";
            case HistoryEntry.TYPE_PAYMENT_CASH: return "💵 Πληρωμή με Μετρητά";
            case HistoryEntry.TYPE_PAYMENT_CARD: return "💳 Πληρωμή με Κάρτα";
            case HistoryEntry.TYPE_TABLE_CANCELLED: return "❌ Ακύρωση Τραπεζιού";
            case HistoryEntry.TYPE_TABLE_MOVED: return "🔄 Μετακίνηση Τραπεζιού";
            case HistoryEntry.TYPE_TABLE_MERGED: return "🔀 Συγχώνευση Τραπεζιών";
            case HistoryEntry.TYPE_PARTIAL_PAYMENT: return "🧾 Μερική Εξόφληση";
            default: return type;
        }
    }

    private String formatTimestamp(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }
}