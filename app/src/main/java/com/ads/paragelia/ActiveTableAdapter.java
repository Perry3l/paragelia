package com.ads.paragelia;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.util.List;
import java.util.Map;

public class ActiveTableAdapter extends RecyclerView.Adapter<ActiveTableAdapter.TableViewHolder> {

    private final AsyncListDiffer<TableCardData> differ =
            new AsyncListDiffer<>(this, DIFF_CALLBACK);

    private boolean orderOnlyMode = false;

    public void setOrderOnlyMode(boolean enabled) {
        this.orderOnlyMode = enabled;
    }

    // ---------- Διεπαφή ----------
    public interface OnTableInteractionListener {
        void onTableClicked(TableCardData data);
        void onCancelClicked(TableCardData data);
        void onPayClicked(TableCardData data);
        void onPartialClicked(TableCardData data);
        void onMoveClicked(TableCardData data);
        void onPrintTempClicked(TableCardData data);
        void onTableLongClicked(TableCardData data);
    }

    private OnTableInteractionListener listener;

    public void setOnTableInteractionListener(OnTableInteractionListener listener) {
        this.listener = listener;
    }

    public void submitList(List<TableCardData> newList) {
        differ.submitList(newList);
    }

    @NonNull
    @Override
    public TableViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_active_table, parent, false);
        return new TableViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TableViewHolder holder, int position) {
        TableCardData data = differ.getCurrentList().get(position);
        holder.bind(data, listener, orderOnlyMode);
    }

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size();
    }

    // ---------- Data class ----------
    public static class TableCardData {
        public String tableNumber;
        public String details;
        public Map<String, Object> tableData;
        public String status;
        public boolean isEmpty;
        public String customTitle;

        public TableCardData(String tableNumber, String details, Map<String, Object> tableData,
                             String status, boolean isEmpty, String customTitle) {
            this.tableNumber = tableNumber;
            this.details = details;
            this.tableData = tableData;
            this.status = status;
            this.isEmpty = isEmpty;
            this.customTitle = customTitle;
        }
    }

    // ---------- ViewHolder ----------
    static class TableViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardView;
        TextView tvTableInfo;
        LinearLayout buttonsContainer;

        TableViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = (MaterialCardView) itemView;
            tvTableInfo = itemView.findViewById(R.id.tvTableInfo);
            buttonsContainer = itemView.findViewById(R.id.buttonsContainer);
        }

        void bind(TableCardData data, OnTableInteractionListener listener, boolean orderOnlyMode) {
            String title;
            if (data.customTitle != null) {
                title = data.customTitle;
            } else {
                title = "Τραπέζι " + data.tableNumber;
            }
            String text = title + "\n\n" + data.details;
            tvTableInfo.setText(text);

            // Χρώμα ανά κατάσταση
            if (data.isEmpty) {
                cardView.setCardBackgroundColor(Color.LTGRAY);
            } else if ("ordered".equals(data.status)) {
                cardView.setCardBackgroundColor(Color.parseColor("#FFB74D"));
            } else {
                cardView.setCardBackgroundColor(Color.WHITE);
            }

            // Απλό κλικ
            cardView.setOnClickListener(v -> {
                if (listener != null) listener.onTableClicked(data);
            });

            // Παρατεταμένο πάτημα
            cardView.setOnLongClickListener(v -> {
                if (listener != null) listener.onTableLongClicked(data);
                return true;
            });

            // Δημιουργία κουμπιών μόνο αν ΔΕΝ είναι άδειο
            buttonsContainer.removeAllViews();
            if (!data.isEmpty) {
                if ("ordered".equals(data.status)) {
                    addOrderedButtons(buttonsContainer, data, listener, orderOnlyMode);
                } else {
                    addNormalButtons(buttonsContainer, data, listener);
                }
            }
        }

        private void addNormalButtons(LinearLayout parent, TableCardData data,
                                      OnTableInteractionListener listener) {
            // ... (ίδιος κώδικας με πριν, δεν τον αλλάζουμε)
            LinearLayout row1 = new LinearLayout(parent.getContext());
            row1.setOrientation(LinearLayout.HORIZONTAL);

            Button btnCancel = new Button(parent.getContext());
            btnCancel.setText("ΑΚΥΡΩΣΗ");
            btnCancel.setBackgroundColor(Color.parseColor("#F44336"));
            btnCancel.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams btnCancelParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            btnCancelParams.setMargins(0, 0, 8, 0);
            btnCancel.setLayoutParams(btnCancelParams);
            btnCancel.setOnClickListener(v -> {
                if (listener != null) listener.onCancelClicked(data);
            });

            Button btnPay = new Button(parent.getContext());
            btnPay.setText("ΕΚΔΟΣΗ ΑΠΟΔΕΙΞΗΣ");
            btnPay.setBackgroundColor(Color.parseColor("#4CAF50"));
            btnPay.setTextColor(Color.WHITE);
            btnPay.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            btnPay.setOnClickListener(v -> {
                if (listener != null) listener.onPayClicked(data);
            });

            row1.addView(btnCancel);
            row1.addView(btnPay);
            parent.addView(row1);

            Button btnPartial = new Button(parent.getContext());
            btnPartial.setText("ΜΕΡΙΚΗ ΕΞΟΦΛΗΣΗ");
            btnPartial.setBackgroundColor(Color.parseColor("#FF9800"));
            btnPartial.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams btnPartialParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnPartialParams.setMargins(0, 8, 0, 0);
            btnPartial.setLayoutParams(btnPartialParams);
            btnPartial.setOnClickListener(v -> listener.onPartialClicked(data));
            parent.addView(btnPartial);

            Button btnMove = new Button(parent.getContext());
            btnMove.setText("ΜΕΤΑΚΙΝΗΣΗ");
            btnMove.setBackgroundColor(Color.parseColor("#2196F3"));
            btnMove.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams btnMoveParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnMoveParams.setMargins(0, 8, 0, 0);
            btnMove.setLayoutParams(btnMoveParams);
            btnMove.setOnClickListener(v -> {
                if (listener != null) listener.onMoveClicked(data);
            });
            parent.addView(btnMove);
        }

        private void addOrderedButtons(LinearLayout parent, TableCardData data,
                                       OnTableInteractionListener listener, boolean orderOnlyMode) {
            if (orderOnlyMode) {
                // Στη λειτουργία μόνο παραγγελιών: εμφάνιση συνολικού ποσού αντί για κουμπί αναφοράς
                double total = calculateTotalAmount(data.tableData);
                Button btnTotal = new Button(parent.getContext());
                btnTotal.setText(String.format("ΣΥΝΟΛΟ: €%.2f", total));
                btnTotal.setBackgroundColor(Color.parseColor("#2196F3"));
                btnTotal.setTextColor(Color.WHITE);
                btnTotal.setEnabled(false); // δεν κάνει τίποτα
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                params.setMargins(0, 0, 0, 8);
                btnTotal.setLayoutParams(params);
                parent.addView(btnTotal);
            } else {
                // Κανονική λειτουργία: κουμπί εκτύπωσης αναφοράς
                Button btnPrintTemp = new Button(parent.getContext());
                btnPrintTemp.setText("ΑΝΑΦΟΡΑ ΤΡΑΠΕΖΙΟΥ");
                btnPrintTemp.setBackgroundColor(Color.parseColor("#2196F3"));
                btnPrintTemp.setTextColor(Color.WHITE);
                LinearLayout.LayoutParams btnPrintParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                btnPrintParams.setMargins(0, 0, 0, 0);
                btnPrintTemp.setLayoutParams(btnPrintParams);
                btnPrintTemp.setOnClickListener(v -> {
                    if (listener != null) listener.onPrintTempClicked(data);
                });
                parent.addView(btnPrintTemp);
            }

            // Τα υπόλοιπα κουμπιά (ΑΚΥΡΩΣΗ, ΜΕΤΑΚΙΝΗΣΗ) εμφανίζονται πάντα
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 8, 0, 0);
            row.setLayoutParams(rowParams);

            Button btnCancel = new Button(parent.getContext());
            btnCancel.setText("ΑΚΥΡΩΣΗ");
            btnCancel.setBackgroundColor(Color.parseColor("#F44336"));
            btnCancel.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp1.setMargins(0, 0, 8, 0);
            btnCancel.setLayoutParams(lp1);
            btnCancel.setOnClickListener(v -> {
                if (listener != null) listener.onCancelClicked(data);
            });

            Button btnMove = new Button(parent.getContext());
            btnMove.setText("ΜΕΤΑΚΙΝΗΣΗ");
            btnMove.setBackgroundColor(Color.parseColor("#2196F3"));
            btnMove.setTextColor(Color.WHITE);
            btnMove.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            btnMove.setOnClickListener(v -> {
                if (listener != null) listener.onMoveClicked(data);
            });

            row.addView(btnCancel);
            row.addView(btnMove);
            parent.addView(row);
        }

        // Βοηθητική μέθοδος υπολογισμού συνολικού ποσού από τα tableData
        private double calculateTotalAmount(Map<String, Object> tableData) {
            double total = 0.0;
            if (tableData == null) return total;

            // 1. Από push-keys (παλιά δομή)
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
                        int qty = ((Number) item.get("quantity")).intValue();
                        double price = ((Number) item.get("price")).doubleValue();
                        total += qty * price;
                    }
                }
            }

            // 2. Από current_order (συγχωνεύσεις, order‑only mode)
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
    }

    // ---------- DiffUtil ----------
    private static final DiffUtil.ItemCallback<TableCardData> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<TableCardData>() {
                @Override
                public boolean areItemsTheSame(@NonNull TableCardData oldItem,
                                               @NonNull TableCardData newItem) {
                    return oldItem.tableNumber.equals(newItem.tableNumber);
                }

                @Override
                public boolean areContentsTheSame(@NonNull TableCardData oldItem,
                                                  @NonNull TableCardData newItem) {
                    return oldItem.details.equals(newItem.details) &&
                            oldItem.status.equals(newItem.status) &&
                            oldItem.isEmpty == newItem.isEmpty;
                }
            };
}