package com.ads.paragelia;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.database.*;
import java.util.*;

public class ProductSelectionBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_TABLE_NUMBER = "table_number";
    private String tableNumber;
    private DatabaseReference productsRef;
    private List<String> categoryList = new ArrayList<>();
    private Map<String, Map<String, Double>> productsByCategory = new HashMap<>();
    private TabLayout tabCategories;
    private RecyclerView rvProducts, rvSelectedItems;
    private Button btnSubmit;
    private ProductAdapter productAdapter;
    private SelectedItemsAdapter selectedAdapter;
    private List<OrderItem> selectedItems = new ArrayList<>();
    private String currentCategory;

    public static ProductSelectionBottomSheet newInstance(String tableNumber) {
        ProductSelectionBottomSheet fragment = new ProductSelectionBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_TABLE_NUMBER, tableNumber);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_add_products, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tableNumber = getArguments().getString(ARG_TABLE_NUMBER);
        productsRef = FirebaseDatabase.getInstance().getReference("products");

        tabCategories = view.findViewById(R.id.tabCategories);
        rvProducts = view.findViewById(R.id.rvProducts);
        rvSelectedItems = view.findViewById(R.id.rvSelectedItems);
        btnSubmit = view.findViewById(R.id.btnSubmitOrder);

        rvProducts.setLayoutManager(new LinearLayoutManager(getContext()));
        rvSelectedItems.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
        selectedAdapter = new SelectedItemsAdapter(selectedItems, this::removeItem);
        rvSelectedItems.setAdapter(selectedAdapter);

        loadCategoriesAndProducts();

        btnSubmit.setOnClickListener(v -> submitOrder());
    }

    private void loadCategoriesAndProducts() {
        productsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                categoryList.clear();
                productsByCategory.clear();
                for (DataSnapshot catSnap : snapshot.getChildren()) {
                    String category = catSnap.getKey();
                    categoryList.add(category);
                    Map<String, Double> productMap = new HashMap<>();
                    for (DataSnapshot prodSnap : catSnap.getChildren()) {
                        String productName = prodSnap.getKey();
                        Double price = prodSnap.getValue(Double.class);
                        if (price == null) {
                            Object val = prodSnap.getValue();
                            if (val instanceof Long) price = ((Long) val).doubleValue();
                            else price = 0.0;
                        }
                        productMap.put(productName, price);
                    }
                    productsByCategory.put(category, productMap);
                }
                setupTabs();
                if (!categoryList.isEmpty()) {
                    currentCategory = categoryList.get(0);
                    showProductsForCategory(currentCategory);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void setupTabs() {
        for (String cat : categoryList) {
            tabCategories.addTab(tabCategories.newTab().setText(cat));
        }
        tabCategories.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                currentCategory = categoryList.get(tab.getPosition());
                showProductsForCategory(currentCategory);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void showProductsForCategory(String category) {
        Map<String, Double> products = productsByCategory.get(category);
        if (products == null) return;
        List<ProductItem> productList = new ArrayList<>();
        for (Map.Entry<String, Double> entry : products.entrySet()) {
            productList.add(new ProductItem(entry.getKey(), entry.getValue()));
        }
        productAdapter = new ProductAdapter(productList, this::addProduct);
        rvProducts.setAdapter(productAdapter);
    }

    private void addProduct(ProductItem product) {
        // Προεπιλεγμένη ποσότητα = 1. Μπορείς να προσθέσεις dialog για αλλαγή ποσότητας
        boolean found = false;
        for (OrderItem item : selectedItems) {
            if (item.name.equals(product.name)) {
                item.quantity++;
                found = true;
                break;
            }
        }
        if (!found) {
            selectedItems.add(new OrderItem(product.name, 1, product.price));
        }
        selectedAdapter.notifyDataSetChanged();
        Toast.makeText(getContext(), "Προστέθηκε: " + product.name, Toast.LENGTH_SHORT).show();
    }

    private void removeItem(int position) {
        selectedItems.remove(position);
        selectedAdapter.notifyDataSetChanged();
    }

    private void submitOrder() {
        if (selectedItems.isEmpty()) {
            Toast.makeText(getContext(), "Δεν προστέθηκε κανένα προϊόν", Toast.LENGTH_SHORT).show();
            return;
        }

        // Μετατροπή σε Map για Firebase
        List<Map<String, Object>> itemsList = new ArrayList<>();
        for (OrderItem item : selectedItems) {
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

        DatabaseReference ordersRef = FirebaseDatabase.getInstance().getReference("orders");
        String orderId = ordersRef.push().getKey();
        ordersRef.child(orderId).setValue(order)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(getContext(), "Η παραγγελία για τραπέζι " + tableNumber + " στάλθηκε!", Toast.LENGTH_SHORT).show();
                    dismiss();
                })
                .addOnFailureListener(e -> Toast.makeText(getContext(), "Σφάλμα: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    // ----- Adapters -----
    static class ProductItem { String name; double price; ProductItem(String n, double p) { name=n; price=p; } }
    static class OrderItem { String name; int quantity; double price; OrderItem(String n, int q, double p) { name=n; quantity=q; price=p; } }

    static class ProductAdapter extends RecyclerView.Adapter<ProductAdapter.ViewHolder> {
        private List<ProductItem> products;
        private OnProductClickListener listener;
        interface OnProductClickListener { void onProductClick(ProductItem product); }
        ProductAdapter(List<ProductItem> products, OnProductClickListener listener) { this.products=products; this.listener=listener; }
        @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_2, parent, false);
            return new ViewHolder(v);
        }
        @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ProductItem p = products.get(position);
            holder.text1.setText(p.name);
            holder.text2.setText("€" + String.format("%.2f", p.price));
            holder.itemView.setOnClickListener(v -> listener.onProductClick(p));
        }
        @Override public int getItemCount() { return products.size(); }
        class ViewHolder extends RecyclerView.ViewHolder {
            android.widget.TextView text1, text2;
            ViewHolder(View v) { super(v); text1 = v.findViewById(android.R.id.text1); text2 = v.findViewById(android.R.id.text2); }
        }
    }

    static class SelectedItemsAdapter extends RecyclerView.Adapter<SelectedItemsAdapter.ViewHolder> {
        private List<OrderItem> items;
        private OnRemoveClickListener removeListener;
        interface OnRemoveClickListener { void onRemove(int position); }
        SelectedItemsAdapter(List<OrderItem> items, OnRemoveClickListener listener) { this.items=items; this.removeListener=listener; }
        @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
            return new ViewHolder(v);
        }
        @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            OrderItem item = items.get(position);
            holder.textView.setText(item.name + " x" + item.quantity);
            holder.itemView.setOnLongClickListener(v -> { removeListener.onRemove(position); return true; });
        }
        @Override public int getItemCount() { return items.size(); }
        class ViewHolder extends RecyclerView.ViewHolder {
            android.widget.TextView textView;
            ViewHolder(View v) { super(v); textView = v.findViewById(android.R.id.text1); }
        }
    }
}