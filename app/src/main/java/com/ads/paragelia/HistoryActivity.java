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
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends BaseActivity {

    private RecyclerView rvHistory;
    private HistoryAdapter adapter;
    private List<HistoryEntry> historyList = new ArrayList<>();
    private DatabaseReference historyRef;
    private Button btnLoadMore;

    private static final int PAGE_SIZE = 50;
    private String lastKey = null;               // το κλειδί της τελευταίας εγγραφής που φορτώθηκε
    private boolean isLoading = false;
    private boolean allLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        showMemoryOverlay();
        rvHistory = findViewById(R.id.rvHistory);
        btnLoadMore = findViewById(R.id.btnLoadMore);

        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HistoryAdapter(historyList);
        rvHistory.setAdapter(adapter);

        historyRef = FirebaseHelper.getReference("history");

        // Πρώτη φόρτωση
        loadInitialHistory();

        btnLoadMore.setOnClickListener(v -> loadMoreHistory());
    }

    private void loadInitialHistory() {
        isLoading = true;
        Query query = historyRef.orderByKey().limitToLast(PAGE_SIZE);

        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                historyList.clear();
                for (DataSnapshot snap : snapshot.getChildren()) {
                    HistoryEntry entry = snap.getValue(HistoryEntry.class);
                    if (entry != null && !HistoryEntry.TYPE_TABLE_CANCELLED.equals(entry.type)
                            && !HistoryEntry.TYPE_ORDER_COMPLETED.equals(entry.type)) {
                        historyList.add(entry);
                        lastKey = snap.getKey();  // κρατάμε το πρώτο (παλαιότερο) κλειδί
                    }
                }
                // Η Firebase επιστρέφει ταξινομημένα αύξοντα, άρα πρέπει να αντιστρέψουμε
                java.util.Collections.reverse(historyList);
                adapter.notifyDataSetChanged();

                // Αν πήραμε λιγότερα από PAGE_SIZE, σημαίνει ότι φτάσαμε στο τέλος
                allLoaded = (snapshot.getChildrenCount() < PAGE_SIZE);
                updateLoadMoreButton();
                isLoading = false;
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                isLoading = false;
            }
        });
    }

    private void loadMoreHistory() {
        if (isLoading || allLoaded) return;

        isLoading = true;
        // Φορτώνουμε τα αμέσως παλαιότερα: ξεκινάμε από το lastKey, παραλείπουμε το ίδιο το lastKey,
        // και παίρνουμε τα προηγούμενα PAGE_SIZE.
        Query query = historyRef.orderByKey().endAt(lastKey).limitToLast(PAGE_SIZE + 1);

        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<HistoryEntry> olderEntries = new ArrayList<>();
                int count = 0;
                String newLastKey = null;

                for (DataSnapshot snap : snapshot.getChildren()) {
                    // Το πρώτο (παλαιότερο) κλειδί θα είναι το νέο lastKey
                    if (newLastKey == null) {
                        newLastKey = snap.getKey();
                    }
                    // Αν φτάσαμε στο lastKey που ήδη είχαμε, το пропускаμε
                    if (snap.getKey().equals(lastKey)) continue;

                    HistoryEntry entry = snap.getValue(HistoryEntry.class);
                    if (entry != null && !HistoryEntry.TYPE_TABLE_CANCELLED.equals(entry.type)
                            && !HistoryEntry.TYPE_ORDER_COMPLETED.equals(entry.type)) {
                        olderEntries.add(entry);
                        count++;
                    }
                }

                // Προσάρτηση στο τέλος της λίστας (χωρίς αντιστροφή, γιατί είναι ήδη παλαιότερα)
                historyList.addAll(olderEntries);
                adapter.notifyDataSetChanged();
                lastKey = newLastKey;

                // Αν πήραμε λιγότερα από αυτά που ζητήσαμε, τελειώσαμε
                if (count < PAGE_SIZE) {
                    allLoaded = true;
                }
                updateLoadMoreButton();
                isLoading = false;
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                isLoading = false;
            }
        });
    }

    private void updateLoadMoreButton() {
        if (allLoaded) {
            btnLoadMore.setVisibility(View.GONE);
        } else {
            btnLoadMore.setVisibility(View.VISIBLE);
        }
    }

    // ================== Adapter ==================
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