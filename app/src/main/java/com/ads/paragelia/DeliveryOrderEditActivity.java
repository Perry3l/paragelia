package com.ads.paragelia;

import android.app.AlertDialog;
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

import com.google.firebase.database.DatabaseReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DeliveryOrderEditActivity extends BaseActivity {

    public static final String EXTRA_ORDER_ID = "order_id";
    public static final String EXTRA_ORDER_NUMBER = "order_number";
    public static final String EXTRA_CUSTOMER_NAME = "customer_name";
    public static final String EXTRA_CUSTOMER_PHONE = "customer_phone";
    public static final String EXTRA_CUSTOMER_ADDRESS = "customer_address";
    public static final String EXTRA_CUSTOMER_NOTES = "customer_notes";

    private String orderId;
    private String orderNumber;
    private String customerName, customerPhone, customerAddress, customerNotes;
    private List<OrderItem> items = new ArrayList<>();
    private RecyclerView rvItems;
    private ItemsAdapter adapter;
    private DatabaseReference orderRef;

    private static final int REQUEST_ADD_PRODUCT = 2001;
    private EditText etNameRef;
    private EditText etPhoneRef;
    private EditText etAddressRef;
    private EditText etNotesRef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_delivery_order_edit);
        showMemoryOverlay();

        orderId = getIntent().getStringExtra(EXTRA_ORDER_ID);
        orderNumber = getIntent().getStringExtra(EXTRA_ORDER_NUMBER);
        customerName = getIntent().getStringExtra(EXTRA_CUSTOMER_NAME);
        customerPhone = getIntent().getStringExtra(EXTRA_CUSTOMER_PHONE);
        customerAddress = getIntent().getStringExtra(EXTRA_CUSTOMER_ADDRESS);
        customerNotes = getIntent().getStringExtra(EXTRA_CUSTOMER_NOTES);

        orderRef = FirebaseHelper.getReference("delivery_orders").child(orderId).child("current_order");

        TextView tvOrderTitle = findViewById(R.id.tvOrderTitle);
        tvOrderTitle.setText("Παραγγελία #" + orderNumber);

// Κάνουμε τα πεδία προσβάσιμα σε όλη την κλάση για να τα διαβάσουμε στην αποθήκευση
        final EditText etCustomerName = findViewById(R.id.etCustomerName);
        final EditText etCustomerPhone = findViewById(R.id.etCustomerPhone);
        final EditText etCustomerAddress = findViewById(R.id.etCustomerAddress);
        final EditText etCustomerNotes = findViewById(R.id.etCustomerNotes);

        etCustomerName.setText(customerName != null ? customerName : "");
        etCustomerPhone.setText(customerPhone != null ? customerPhone : "");
        etCustomerAddress.setText(customerAddress != null ? customerAddress : "");
        etCustomerNotes.setText(customerNotes != null ? customerNotes : "");

        // Αποθηκεύουμε τα references των EditText σε μεταβλητές κλάσης για να τα βρει η saveChanges
        this.etNameRef = etCustomerName;
        this.etPhoneRef = etCustomerPhone;
        this.etAddressRef = etCustomerAddress;
        this.etNotesRef = etCustomerNotes;

        rvItems = findViewById(R.id.rvOrderItems);
        rvItems.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ItemsAdapter(items);
        rvItems.setAdapter(adapter);

        // Φόρτωση υπαρχόντων items από Firebase
        loadItems();

        Button btnAddProduct = findViewById(R.id.btnAddProduct);
        Button btnSave = findViewById(R.id.btnSave);
        Button btnCancelOrder = findViewById(R.id.btnCancelOrder);

        btnAddProduct.setOnClickListener(v -> {
            ProductSelectionBottomSheet bottomSheet = ProductSelectionBottomSheet.newInstance("DL-" + orderNumber);
            bottomSheet.show(getSupportFragmentManager(), "add_product");
            bottomSheet.setOnDismissListener(dialog -> {
                // Μετά το κλείσιμο, ελέγχουμε αν υπάρχουν νέα items στο CurrentTableHolder;
                // Εναλλακτικά, μπορούμε να ανακτήσουμε τα επιλεγμένα items από την bottom sheet.
                // Για απλότητα, θα επαναφορτώσουμε όλη την παραγγελία.
                loadItems();
            });
        });

        btnSave.setOnClickListener(v -> saveChanges());
        btnCancelOrder.setOnClickListener(v -> cancelOrder());
    }

    private void loadItems() {
        // Παίρνουμε τη βάση της παραγγελίας (π.χ. το DL-9)
        DatabaseReference baseOrderRef = FirebaseHelper.getReference("delivery_orders").child(orderId);

        baseOrderRef.addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
            @Override
            public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snapshot) {
                com.google.firebase.database.DataSnapshot targetItemsSnap = null;

                // 1. Έλεγχος για τη ΝΕΑ σωστή δομή (DL-X / current_order / items)
                if (snapshot.hasChild("current_order") && snapshot.child("current_order").hasChild("items")) {
                    targetItemsSnap = snapshot.child("current_order").child("items");
                    orderRef = snapshot.child("current_order").getRef(); // Ενημέρωση του ref για την Αποθήκευση
                }
                // 2. Έλεγχος για την ΠΑΛΙΑ δομή (DL-X / τυχαίο_κλειδί / current_order / items)
                else {
                    for (com.google.firebase.database.DataSnapshot child : snapshot.getChildren()) {
                        if (child.hasChild("current_order") && child.child("current_order").hasChild("items")) {
                            targetItemsSnap = child.child("current_order").child("items");
                            orderRef = child.child("current_order").getRef(); // Ενημέρωση του ref για την Αποθήκευση
                            break;
                        }
                    }
                }

                // Αν βρέθηκαν είδη, τα περνάμε στη λίστα
                if (targetItemsSnap != null) {
                    items.clear();
                    for (com.google.firebase.database.DataSnapshot itemSnap : targetItemsSnap.getChildren()) {
                        Map<String, Object> map = (Map<String, Object>) itemSnap.getValue();
                        if (map != null) {
                            String name = (String) map.get("name");
                            int quantity = ((Number) map.get("quantity")).intValue();
                            double price = ((Number) map.get("price")).doubleValue();
                            String comment = (String) map.get("comment");
                            double vatPercent = map.containsKey("vatPercent") ? ((Number) map.get("vatPercent")).doubleValue() : 13.0;
                            items.add(new OrderItem(name, quantity, price, comment, vatPercent));
                        }
                    }
                    adapter.notifyDataSetChanged();
                } else {
                    Toast.makeText(DeliveryOrderEditActivity.this, "Δεν βρέθηκαν είδη", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) {
                Toast.makeText(DeliveryOrderEditActivity.this, "Σφάλμα φόρτωσης ειδών", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void saveChanges() {
        // 1. Προετοιμασία των items
        List<Map<String, Object>> itemsMap = new ArrayList<>();
        for (OrderItem item : items) {
            Map<String, Object> map = new HashMap<>();
            map.put("name", item.name);
            map.put("quantity", item.quantity);
            map.put("price", item.price);
            map.put("comment", item.comment != null ? item.comment : "");
            map.put("vatPercent", item.vatPercent);
            itemsMap.add(map);
        }

        // 2. Προετοιμασία των στοιχείων του πελάτη
        Map<String, Object> customerMap = new HashMap<>();
        customerMap.put("name", etNameRef.getText().toString().trim());
        customerMap.put("phone", etPhoneRef.getText().toString().trim());
        customerMap.put("address", etAddressRef.getText().toString().trim());
        customerMap.put("notes", etNotesRef.getText().toString().trim());

        // 3. Ομαδική ενημέρωση στο Firebase (updateChildren)
        Map<String, Object> updates = new HashMap<>();
        updates.put("items", itemsMap);
        updates.put("customer", customerMap);

        orderRef.updateChildren(updates)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Οι αλλαγές αποθηκεύτηκαν επιτυχώς!", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .addOnFailureListener(e -> Toast.makeText(this, "Σφάλμα αποθήκευσης: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void cancelOrder() {
        new AlertDialog.Builder(this)
                .setTitle("Ακύρωση παραγγελίας")
                .setMessage("Θέλετε να ακυρώσετε οριστικά την παραγγελία #" + orderNumber + ";")
                .setPositiveButton("Ναι", (dialog, which) -> {
                    DatabaseReference deliveryRef = FirebaseHelper.getReference("delivery_orders").child(orderId);
                    deliveryRef.removeValue()
                            .addOnSuccessListener(aVoid -> {
                                Toast.makeText(this, "Η παραγγελία ακυρώθηκε", Toast.LENGTH_SHORT).show();
                                finish();
                            })
                            .addOnFailureListener(e -> Toast.makeText(this, "Σφάλμα ακύρωσης", Toast.LENGTH_SHORT).show());
                })
                .setNegativeButton("Όχι", null)
                .show();
    }

    private class ItemsAdapter extends RecyclerView.Adapter<ItemsAdapter.ViewHolder> {
        private List<OrderItem> items;

        ItemsAdapter(List<OrderItem> items) { this.items = items; }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_editable_order_item, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            OrderItem item = items.get(position);
            holder.tvName.setText(item.name);
            holder.tvPrice.setText(String.format("%.2f€", item.price));
            holder.etQuantity.setText(String.valueOf(item.quantity));
            holder.etComment.setText(item.comment != null ? item.comment : "");

            holder.btnDelete.setOnClickListener(v -> {
                items.remove(position);
                notifyItemRemoved(position);
            });

            holder.etQuantity.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    try {
                        int newQty = Integer.parseInt(holder.etQuantity.getText().toString());
                        if (newQty <= 0) {
                            items.remove(position);
                            notifyItemRemoved(position);
                        } else {
                            item.quantity = newQty;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            });

            holder.etComment.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    item.comment = holder.etComment.getText().toString();
                }
            });
        }

        @Override
        public int getItemCount() { return items.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvPrice;
            EditText etQuantity, etComment;
            Button btnDelete;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvItemName);
                tvPrice = itemView.findViewById(R.id.tvItemPrice);
                etQuantity = itemView.findViewById(R.id.etQuantity);
                etComment = itemView.findViewById(R.id.etComment);
                btnDelete = itemView.findViewById(R.id.btnDeleteItem);
            }
        }
    }

    static class OrderItem {
        String name;
        int quantity;
        double price;
        String comment;
        double vatPercent;

        OrderItem(String name, int quantity, double price, String comment, double vatPercent) {
            this.name = name; this.quantity = quantity; this.price = price;
            this.comment = comment; this.vatPercent = vatPercent;
        }
    }
}