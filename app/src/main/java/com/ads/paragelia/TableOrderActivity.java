package com.ads.paragelia;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.firebase.database.*;
import java.util.*;

public class TableOrderActivity extends AppCompatActivity {

    private static final String EXTRA_TABLE_NUMBER = "table_number";
    private String tableNumber;
    private DatabaseReference activeBillsRef;
    private DatabaseReference productsRef;

    // UI components
    private TextView tvTableTitle,tvCartTotal;
    private RecyclerView rvCart, rvCategories, rvProducts;
    private Button btnViewFullOrder, btnSaveChanges;

    // Data
    private List<CartItem> cartItems = new ArrayList<>();         // items του τραπεζιού
    private List<String> categoryList = new ArrayList<>();
    private Map<String, Map<String, Double>> productsByCategory = new HashMap<>();
    private String currentCategory = "";

    // Adapters
    private CartAdapter cartAdapter;
    private CategoryAdapter categoryAdapter;
    private ProductAdapter productAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_table_order);

        tableNumber = getIntent().getStringExtra(EXTRA_TABLE_NUMBER);
        tvTableTitle = findViewById(R.id.tvTableTitle);
        rvCart = findViewById(R.id.rvCart);
        rvCategories = findViewById(R.id.rvCategories);
        rvProducts = findViewById(R.id.rvProducts);
        btnViewFullOrder = findViewById(R.id.btnViewFullOrder);
        btnSaveChanges = findViewById(R.id.btnSaveChanges);
        tvCartTotal = findViewById(R.id.tvCartTotal);

        tvTableTitle.setText("Τραπέζι " + tableNumber);
        activeBillsRef = FirebaseDatabase.getInstance().getReference("active_bills").child(tableNumber);
        productsRef = FirebaseDatabase.getInstance().getReference("products");

        rvCart.setLayoutManager(new LinearLayoutManager(this));
        rvCategories.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvProducts.setLayoutManager(new LinearLayoutManager(this));

        cartAdapter = new CartAdapter(cartItems);
        rvCart.setAdapter(cartAdapter);

        loadExistingOrders();
        loadCategoriesAndProducts();

        btnViewFullOrder.setOnClickListener(v -> showFullOrderPopup());
        btnSaveChanges.setOnClickListener(v -> saveAllChanges());
    }
    private void updateTotal() {
        double total = 0;
        for (CartItem item : cartItems) total += item.price * item.quantity;
        tvCartTotal.setText("Σύνολο: €" + String.format("%.2f", total));
    }
    private void loadExistingOrders() {
        activeBillsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                cartItems.clear();
                for (DataSnapshot orderSnapshot : snapshot.getChildren()) {
                    Map<String, Object> order = (Map<String, Object>) orderSnapshot.getValue();
                    if (order != null && order.get("items") instanceof List) {
                        List<Map<String, Object>> items = (List<Map<String, Object>>) order.get("items");
                        for (Map<String, Object> item : items) {
                            String name = (String) item.get("name");

                            // Ασφαλής ανάγνωση quantity
                            Object qtyObj = item.get("quantity");
                            int quantity = 0;
                            if (qtyObj instanceof Long) {
                                quantity = ((Long) qtyObj).intValue();
                            } else if (qtyObj instanceof Integer) {
                                quantity = (Integer) qtyObj;
                            }

                            // Ασφαλής ανάγνωση price
                            Object priceObj = item.get("price");
                            double price = 0.0;
                            if (priceObj instanceof Double) {
                                price = (Double) priceObj;
                            } else if (priceObj instanceof Long) {
                                price = ((Long) priceObj).doubleValue();
                            } else if (priceObj instanceof Integer) {
                                price = ((Integer) priceObj).doubleValue();
                            }

                            String comment = item.containsKey("comment") ? (String) item.get("comment") : "";
                            cartItems.add(new CartItem(name, quantity, price, comment, orderSnapshot.getKey()));
                        }
                    }
                }
                cartAdapter.notifyDataSetChanged();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(TableOrderActivity.this, "Σφάλμα φόρτωσης", Toast.LENGTH_SHORT).show();
            }
        });
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
                        if (price == null) price = 0.0;
                        productMap.put(productName, price);
                    }
                    productsByCategory.put(category, productMap);
                }
                if (!categoryList.isEmpty()) {
                    currentCategory = categoryList.get(0);
                    setupCategoryAdapter();
                    showProductsForCategory(currentCategory);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(TableOrderActivity.this, "Σφάλμα προϊόντων", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupCategoryAdapter() {
        categoryAdapter = new CategoryAdapter(categoryList, category -> {
            currentCategory = category;
            showProductsForCategory(currentCategory);
        });
        rvCategories.setAdapter(categoryAdapter);
    }

    private void showProductsForCategory(String category) {
        Map<String, Double> products = productsByCategory.get(category);
        if (products == null) return;
        List<ProductItem> productList = new ArrayList<>();
        for (Map.Entry<String, Double> entry : products.entrySet()) {
            productList.add(new ProductItem(entry.getKey(), entry.getValue()));
        }
        productAdapter = new ProductAdapter(productList, this::addProductToCart);
        rvProducts.setAdapter(productAdapter);
    }

    private void addProductToCart(ProductItem product) {
        // Ελέγχουμε αν υπάρχει ήδη το ίδιο προϊόν (με ίδιο σχόλιο – εδώ απλά χωρίς σχόλιο)
        boolean found = false;
        for (CartItem item : cartItems) {
            if (item.name.equals(product.name) && item.comment.isEmpty()) {
                item.quantity++;
                found = true;
                break;
            }
        }
        if (!found) {
            cartItems.add(new CartItem(product.name, 1, product.price, "", null));
        }
        cartAdapter.notifyDataSetChanged();
        updateTotal();  // <-- εδώ
        Toast.makeText(this, "Προστέθηκε: " + product.name, Toast.LENGTH_SHORT).show();
    }

    private void showFullOrderPopup() {
        if (cartItems.isEmpty()) {
            Toast.makeText(this, "Δεν υπάρχουν προϊόντα", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        double total = 0;
        for (CartItem item : cartItems) {
            sb.append(item.name).append(" x").append(item.quantity);
            if (!item.comment.isEmpty()) sb.append(" (").append(item.comment).append(")");
            sb.append("\n");
            total += item.price * item.quantity;
        }
        sb.append("\nΣύνολο: €").append(String.format("%.2f", total));
        new AlertDialog.Builder(this)
                .setTitle("Πλήρης Παραγγελία - Τραπέζι " + tableNumber)
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void saveAllChanges() {
        // Αντικαθιστούμε όλο το active_bills/tableNumber με νέα δομή
        // Κάθε orderId θα έχει ένα order object με items
        // Εδώ απλά θα αποθηκεύσουμε έναν νέο order με id "merged_order"
        Map<String, Object> newOrder = new HashMap<>();
        List<Map<String, Object>> itemsList = new ArrayList<>();
        for (CartItem item : cartItems) {
            Map<String, Object> map = new HashMap<>();
            map.put("name", item.name);
            map.put("quantity", item.quantity);
            map.put("price", item.price);
            map.put("comment", item.comment);
            itemsList.add(map);
        }
        newOrder.put("items", itemsList);
        newOrder.put("timestamp", System.currentTimeMillis());
        newOrder.put("tableNumber", Integer.parseInt(tableNumber));

        activeBillsRef.child("final_order").setValue(newOrder)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Αποθηκεύτηκε", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .addOnFailureListener(e -> Toast.makeText(this, "Σφάλμα αποθήκευσης", Toast.LENGTH_SHORT).show());
    }

    // ---------- Adapter for Cart (with edit quantity & comment) ----------
    class CartAdapter extends RecyclerView.Adapter<CartAdapter.ViewHolder> {
        private List<CartItem> items;
        CartAdapter(List<CartItem> items) { this.items = items; }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_cart_editable, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            CartItem item = items.get(position);
            holder.tvName.setText(item.name);
            holder.tvPrice.setText(String.format("€%.2f", item.price));
            holder.etQuantity.setText(String.valueOf(item.quantity));
            holder.etComment.setText(item.comment);

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
                        updateTotal();
                    } catch (NumberFormatException e) {}
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
            ViewHolder(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvCartItemName);
                tvPrice = v.findViewById(R.id.tvCartItemPrice);
                etQuantity = v.findViewById(R.id.etCartItemQuantity);
                etComment = v.findViewById(R.id.etCartItemComment);
            }
        }
    }

    // ---------- Other adapters ----------
    static class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.ViewHolder> {
        List<String> categories;
        OnCategoryClickListener listener;
        interface OnCategoryClickListener { void onCategoryClick(String category); }
        CategoryAdapter(List<String> categories, OnCategoryClickListener listener) {
            this.categories = categories; this.listener = listener;
        }
        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setPadding(32, 16, 32, 16);
            tv.setTextSize(16f);
            tv.setBackgroundResource(android.R.drawable.btn_default);
            return new ViewHolder(tv);
        }
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String cat = categories.get(position);
            holder.textView.setText(cat);
            holder.textView.setOnClickListener(v -> listener.onCategoryClick(cat));
        }
        @Override
        public int getItemCount() { return categories.size(); }
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textView;
            ViewHolder(TextView tv) { super(tv); textView = tv; }
        }
    }

    static class ProductAdapter extends RecyclerView.Adapter<ProductAdapter.ViewHolder> {
        List<ProductItem> products;
        OnProductClickListener listener;
        interface OnProductClickListener { void onProductClick(ProductItem product); }
        ProductAdapter(List<ProductItem> products, OnProductClickListener listener) {
            this.products = products; this.listener = listener;
        }
        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_2, parent, false);
            return new ViewHolder(v);
        }
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ProductItem p = products.get(position);
            holder.text1.setText(p.name);
            holder.text2.setText("€" + String.format("%.2f", p.price));
            holder.itemView.setOnClickListener(v -> listener.onProductClick(p));
        }
        @Override
        public int getItemCount() { return products.size(); }
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView text1, text2;
            ViewHolder(View v) { super(v); text1 = v.findViewById(android.R.id.text1); text2 = v.findViewById(android.R.id.text2); }
        }
    }

    // ---------- Data classes ----------
    static class CartItem {
        String name; int quantity; double price; String comment; String orderId;
        CartItem(String name, int quantity, double price, String comment, String orderId) {
            this.name = name; this.quantity = quantity; this.price = price; this.comment = comment; this.orderId = orderId;
        }
    }
    static class ProductItem { String name; double price; ProductItem(String n, double p) { name=n; price=p; } }
}