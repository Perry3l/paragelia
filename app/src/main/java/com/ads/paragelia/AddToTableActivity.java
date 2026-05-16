package com.ads.paragelia;

import android.content.Intent;
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

import com.google.firebase.database.DatabaseReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AddToTableActivity extends BaseActivity {

    private TextView tvTableNumber;
    private Button btnSelectProduct, btnSubmit;
    private RecyclerView itemsRecyclerView;
    private ItemsAdapter itemsAdapter;
    private List<OrderItem> orderItems = new ArrayList<>();
    private String tableNumber;

    private static final int REQUEST_SELECT_PRODUCT = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_to_table);

        tvTableNumber = findViewById(R.id.tvTableNumber);
        btnSelectProduct = findViewById(R.id.btnSelectProduct);
        btnSubmit = findViewById(R.id.btnSubmit);
        itemsRecyclerView = findViewById(R.id.itemsRecyclerView);

        tableNumber = getIntent().getStringExtra("TABLE_NUMBER");
        tvTableNumber.setText(tableNumber);

        itemsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        itemsAdapter = new ItemsAdapter(orderItems);
        itemsRecyclerView.setAdapter(itemsAdapter);

        btnSelectProduct.setOnClickListener(v -> {
            Intent intent = new Intent(AddToTableActivity.this, SelectProductActivity.class);
            startActivityForResult(intent, REQUEST_SELECT_PRODUCT);
        });

        btnSubmit.setOnClickListener(v -> submitOrder());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SELECT_PRODUCT && resultCode == RESULT_OK && data != null) {
            String name = data.getStringExtra("product_name");
            int quantity = data.getIntExtra("quantity", 1);
            double price = data.getDoubleExtra("price", 0.0);
            orderItems.add(new OrderItem(name, quantity, price));
            itemsAdapter.notifyItemInserted(orderItems.size() - 1);
        }
    }

    private void submitOrder() {
        if (orderItems.isEmpty()) {
            Toast.makeText(this, "Πρόσθεσε τουλάχιστον ένα προϊόν", Toast.LENGTH_SHORT).show();
            return;
        }

        List<Map<String, Object>> itemsList = new ArrayList<>();
        for (OrderItem item : orderItems) {
            Map<String, Object> map = new HashMap<>();
            map.put("name", item.name);
            map.put("quantity", item.quantity);
            map.put("price", item.price);
            itemsList.add(map);
        }

        Map<String, Object> order = new HashMap<>();
        order.put("tableNumber", Integer.parseInt(tableNumber));
        order.put("items", itemsList);
        order.put("timestamp", System.currentTimeMillis());
        order.put("status", "pending");
        order.put("deviceRole", "client");

        DatabaseReference ordersRef = FirebaseHelper.getReference("orders");
        String orderId = ordersRef.push().getKey();
        ordersRef.child(orderId).setValue(order)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(AddToTableActivity.this, "Η παραγγελία για τραπέζι " + tableNumber + " στάλθηκε!", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .addOnFailureListener(e -> Toast.makeText(AddToTableActivity.this, "Σφάλμα: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private static class OrderItem {
        String name; int quantity; double price;
        OrderItem(String name, int quantity, double price) {
            this.name = name; this.quantity = quantity; this.price = price;
        }
    }

    private class ItemsAdapter extends RecyclerView.Adapter<ItemsAdapter.ViewHolder> {
        private List<OrderItem> items;
        ItemsAdapter(List<OrderItem> items) { this.items = items; }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_2, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            OrderItem item = items.get(position);
            holder.text1.setText(item.name + " x" + item.quantity);
            holder.text2.setText("€" + (item.price * item.quantity));
        }

        @Override
        public int getItemCount() { return items.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView text1, text2;
            ViewHolder(View itemView) {
                super(itemView);
                text1 = itemView.findViewById(android.R.id.text1);
                text2 = itemView.findViewById(android.R.id.text2);
            }
        }
    }
}