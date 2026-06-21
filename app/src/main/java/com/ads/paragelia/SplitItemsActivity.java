package com.ads.paragelia;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class SplitItemsActivity extends BaseActivity {

    private TextView tvTableInfo, tvRemainingTotal, tvSplitTotal;
    private RecyclerView rvItems;
    private Button btnAddPart, btnFinish;

    private String tableNumber;
    private List<SplitItem> items = new ArrayList<>();
    private List<List<OrderItem>> splitParts = new ArrayList<>();
    private double totalRemaining = 0.0;

    private SplitItemsAdapter adapter;

    public static class OrderItem {
        String name;
        int quantity;
        double price;
        String comment;
        double vatPercent;

        public OrderItem() {}

        public OrderItem(String name, int quantity, double price, String comment) {
            this.name = name;
            this.quantity = quantity;
            this.price = price;
            this.comment = comment;
            this.vatPercent = 13;
        }

        public OrderItem(String name, int quantity, double price, String comment, double vatPercent) {
            this.name = name;
            this.quantity = quantity;
            this.price = price;
            this.comment = comment;
            this.vatPercent = vatPercent;
        }
    }

    static class SplitItem {
        String name;
        int originalQty;
        int selectedQty;
        double unitPrice;
        String comment;
        double vatPercent;

        SplitItem(String name, int qty, double price, String comment, double vatPercent) {
            this.name = name;
            this.originalQty = qty;
            this.unitPrice = price;
            this.comment = comment;
            this.vatPercent = vatPercent;
            this.selectedQty = 0;
        }

        double getTotalSelected() {
            return selectedQty * unitPrice;
        }
    }
    // --------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_split_items);
        showMemoryOverlay();
        tableNumber = getIntent().getStringExtra("table_number");
        Map<String, Object> tableData = CurrentTableHolder.getTableData();
        if (tableData != null) {
            parseItemsFromTableData(tableData);
            CurrentTableHolder.clear();
        }

        tvTableInfo = findViewById(R.id.tvTableInfo);
        tvRemainingTotal = findViewById(R.id.tvRemainingTotal);
        tvSplitTotal = findViewById(R.id.tvSplitTotal);
        rvItems = findViewById(R.id.rvItems);
        btnAddPart = findViewById(R.id.btnAddPart);
        btnFinish = findViewById(R.id.btnFinish);

        boolean isPartial = getIntent().getBooleanExtra("is_partial", false);

        tvTableInfo.setText("Τραπέζι " + tableNumber + " - Διαχωρισμός");


        adapter = new SplitItemsAdapter(items, this::updateTotals);
        rvItems.setLayoutManager(new LinearLayoutManager(this));
        rvItems.setAdapter(adapter);

        updateTotals();

        btnAddPart.setOnClickListener(v -> addCurrentPart());
        btnFinish.setOnClickListener(v -> finishAndReturn());
    }

    private void parseItemsFromTableData(Map<String, Object> tableData) {
        if (tableData == null) return;

        List<OrderItem> allItems = new ArrayList<>();

        for (Map.Entry<String, Object> entry : tableData.entrySet()) {
            if (entry.getValue() instanceof Map) {
                Map<String, Object> order = (Map<String, Object>) entry.getValue();
                Object itemsObj = order.get("items");
                if (itemsObj instanceof List) {
                    List<Map<String, Object>> itemsList = (List<Map<String, Object>>) itemsObj;
                    for (Map<String, Object> itemMap : itemsList) {
                        String name = (String) itemMap.get("name");
                        Object qtyObj = itemMap.get("quantity");
                        int qty = (qtyObj instanceof Number) ? ((Number) qtyObj).intValue() : 1;
                        Object priceObj = itemMap.get("price");
                        double price = (priceObj instanceof Number) ? ((Number) priceObj).doubleValue() : 0.0;
                        String comment = (String) itemMap.get("comment");
                        if (comment == null) comment = "";

                        double vatPercent = 13.0;
                        if (itemMap.containsKey("vatPercent")) {
                            vatPercent = ((Number) itemMap.get("vatPercent")).doubleValue();
                        }

                        allItems.add(new OrderItem(name, qty, price, comment, vatPercent));
                    }
                }
            }
        }

        for (OrderItem oi : allItems) {
            boolean found = false;
            for (SplitItem si : items) {
                if (si.name.equals(oi.name) && si.comment.equals(oi.comment) && si.vatPercent == oi.vatPercent) {
                    si.originalQty += oi.quantity;
                    found = true;
                    break;
                }
            }
            if (!found) {
                items.add(new SplitItem(oi.name, oi.quantity, oi.price, oi.comment, oi.vatPercent));
            }
        }

        totalRemaining = 0;
        for (SplitItem si : items) {
            totalRemaining += si.originalQty * si.unitPrice;
        }
    }

    private void updateTotals() {
        double splitSum = 0;
        for (SplitItem item : items) {
            splitSum += item.getTotalSelected();
        }
        tvSplitTotal.setText("Τρέχον μέρος: €" + String.format("%.2f", splitSum));
        tvRemainingTotal.setText("Συνολικό υπόλοιπο: €" + String.format("%.2f", totalRemaining));
    }

    private void addCurrentPart() {
        List<OrderItem> part = new ArrayList<>();
        double partTotal = 0;

        for (SplitItem si : items) {
            if (si.selectedQty > 0) {

                part.add(new OrderItem(si.name, si.selectedQty, si.unitPrice, si.comment, si.vatPercent));
                partTotal += si.selectedQty * si.unitPrice;
            }
        }

        if (part.isEmpty()) {
            Toast.makeText(this, "Δεν επιλέξατε κανένα είδος", Toast.LENGTH_SHORT).show();
            return;
        }

        splitParts.add(part);

        Iterator<SplitItem> iterator = items.iterator();
        while (iterator.hasNext()) {
            SplitItem si = iterator.next();
            si.originalQty -= si.selectedQty;
            si.selectedQty = 0;
            if (si.originalQty == 0) {
                iterator.remove();
            }
        }

        totalRemaining -= partTotal;

        adapter.notifyDataSetChanged();
        updateTotals();

        Toast.makeText(this, "Το μέρος προστέθηκε (€" + String.format("%.2f", partTotal) + ")", Toast.LENGTH_SHORT).show();

        if (items.isEmpty()) {
            Toast.makeText(this, "Όλα τα είδη κατανεμήθηκαν!", Toast.LENGTH_SHORT).show();
            finishAndReturn();
        }
    }

    private void finishAndReturn() {
        if (!items.isEmpty()) {
            addCurrentPart();
        }

        Intent resultIntent = new Intent();
        resultIntent.putExtra("split_parts", new Gson().toJson(splitParts));
        resultIntent.putExtra("is_partial", getIntent().getBooleanExtra("is_partial", false));
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    private class SplitItemsAdapter extends RecyclerView.Adapter<SplitItemsAdapter.ViewHolder> {
        private List<SplitItem> items;
        private Runnable onQuantityChanged;

        SplitItemsAdapter(List<SplitItem> items, Runnable onQuantityChanged) {
            this.items = items;
            this.onQuantityChanged = onQuantityChanged;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_split, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            SplitItem item = items.get(position);
            holder.tvName.setText(item.name);
            if (!item.comment.isEmpty()) {
                holder.tvComment.setText("(" + item.comment + ")");
                holder.tvComment.setVisibility(View.VISIBLE);
            } else {
                holder.tvComment.setVisibility(View.GONE);
            }
            holder.tvPrice.setText(String.format("€%.2f", item.unitPrice));
            holder.tvAvailable.setText("Διαθέσιμα: " + item.originalQty);

            if (holder.etQuantity.getTag() instanceof android.text.TextWatcher) {
                holder.etQuantity.removeTextChangedListener((android.text.TextWatcher) holder.etQuantity.getTag());
            }

            holder.etQuantity.setText(String.valueOf(item.selectedQty));

            android.text.TextWatcher watcher = new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(android.text.Editable s) {
                    String str = s.toString().trim();
                    int qty = 0;
                    if (!str.isEmpty()) {
                        try {
                            qty = Integer.parseInt(str);
                        } catch (NumberFormatException e) {
                            qty = 0;
                        }
                    }
                    if (qty < 0) qty = 0;
                    if (qty > item.originalQty) qty = item.originalQty;
                    item.selectedQty = qty;
                    // Ενημέρωση πεδίου αν διορθώθηκε
                    if (!String.valueOf(qty).equals(holder.etQuantity.getText().toString())) {
                        holder.etQuantity.setText(String.valueOf(qty));
                    }
                    onQuantityChanged.run();
                }
            };
            holder.etQuantity.addTextChangedListener(watcher);
            holder.etQuantity.setTag(watcher);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvComment, tvPrice, tvAvailable;
            EditText etQuantity;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvItemName);
                tvComment = itemView.findViewById(R.id.tvItemComment);
                tvPrice = itemView.findViewById(R.id.tvItemPrice);
                tvAvailable = itemView.findViewById(R.id.tvAvailable);
                etQuantity = itemView.findViewById(R.id.etQuantity);
            }
        }
    }
}