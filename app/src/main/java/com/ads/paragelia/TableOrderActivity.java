package com.ads.paragelia;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Layout;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ads.paragelia.paroxos.EpsilonIntegrationHelper;
import com.ads.paragelia.paroxos.SendResponse;
import com.google.firebase.database.*;
import com.zcs.sdk.print.PrnStrFormat;

import java.text.SimpleDateFormat;
import java.util.*;

public class TableOrderActivity extends BaseActivity {

    private static final String EXTRA_TABLE_NUMBER = "table_number";
    private String tableNumber;
    private DatabaseReference activeBillsRef;
    private DatabaseReference productsRef;

    private TextView tvTableTitle,tvCartTotal;
    private RecyclerView rvCart, rvCategories, rvProducts;
    private Button btnViewFullOrder, btnSaveChanges;

    private List<CartItem> cartItems = new ArrayList<>();
    private List<String> categoryList = new ArrayList<>();
    private Map<String, List<ProductItem>> productsByCategory = new HashMap<>();
    private String currentCategory = "";

    private CartAdapter cartAdapter;
    private CategoryAdapter categoryAdapter;
    private ProductAdapter productAdapter;
    private String currentTableMark = "";
    private String currentTableUid = "";
    private String currentTableQrUrl = "";
    private String currentTableFiscalTime = "";
    private boolean hadExistingOrders = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_table_order);
        showMemoryOverlay();
        tableNumber = getIntent().getStringExtra(EXTRA_TABLE_NUMBER);
        tvTableTitle = findViewById(R.id.tvTableTitle);
        rvCart = findViewById(R.id.rvCart);
        rvCategories = findViewById(R.id.rvCategories);
        rvProducts = findViewById(R.id.rvProducts);
        btnViewFullOrder = findViewById(R.id.btnViewFullOrder);
        btnSaveChanges = findViewById(R.id.btnSaveChanges);
        tvCartTotal = findViewById(R.id.tvCartTotal);

        Button btnReportOpenTable = findViewById(R.id.btnReportOpenTable);

        btnReportOpenTable.setOnClickListener(v -> showTableReportOptions());

        tvTableTitle.setText("Τραπέζι " + tableNumber);
        activeBillsRef = FirebaseHelper.getReference("active_bills").child(tableNumber);
        productsRef = FirebaseHelper.getReference("products");

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

    private void showTableReportOptions() {
        String[] options = {"Προβολή στην Οθόνη", "Εκτύπωση Αναφοράς"};

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Αναφορά Τραπεζιού " + tableNumber)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showReportDialog();
                    } else {
                        printTableReport();
                    }
                })
                .show();
    }

    private void showReportDialog() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        layout.setBackgroundColor(Color.WHITE);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("ΑΝΑΦΟΡΑ ΑΝΟΙΚΤΟΥ ΤΡΑΠΕΖΙΟΥ\n(ΔΕΛΤΙΟ ΠΑΡΑΓΓΕΛΙΑΣ 8.6)");
        tvTitle.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(Color.BLACK);
        tvTitle.setTextSize(18);
        layout.addView(tvTitle);

        TextView tvTable = new TextView(this);
        String displayTime = (currentTableFiscalTime != null && !currentTableFiscalTime.isEmpty())
                ? currentTableFiscalTime
                : new SimpleDateFormat("HH:mm:ss").format(new Date());

        tvTable.setText("\nΤΡΑΠΕΖΙ: " + tableNumber + "\nΩΡΑ ΣΗΜΑΝΣΗΣ: " + displayTime);
        tvTable.setTextColor(Color.BLACK);
        layout.addView(tvTable);

        TextView tvItemsHeader = new TextView(this);
        tvItemsHeader.setText("\nΠΕΡΙΓΡΑΦΗ          ΠΟΣ.    ΑΞΙΑ");
        tvItemsHeader.setTypeface(Typeface.MONOSPACE);
        layout.addView(tvItemsHeader);

        double totalAmount = 0;
        for (CartItem item : cartItems) {
            String line = String.format(Locale.getDefault(), "%-18s %3d %7.2f€",
                    item.name, item.quantity, item.price * item.quantity);
            TextView tvItem = new TextView(this);
            tvItem.setText(line);
            tvItem.setTypeface(Typeface.MONOSPACE);
            layout.addView(tvItem);
            totalAmount += item.price * item.quantity;
        }

        TextView tvTotal = new TextView(this);
        tvTotal.setText(String.format(Locale.getDefault(), "\nΣΥΝΟΛΟ: %.2f€", totalAmount));
        tvTotal.setGravity(Gravity.END);
        tvTotal.setTypeface(null, Typeface.BOLD);
        tvTotal.setTextSize(20);
        tvTotal.setTextColor(Color.BLACK);
        layout.addView(tvTotal);

        TextView tvFiscal = new TextView(this);
        tvFiscal.setText("\n--- ΦΟΡΟΛΟΓΙΚΑ ΣΤΟΙΧΕΙΑ ΑΑΔΕ ---");
        tvFiscal.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        tvFiscal.setTextSize(12);
        layout.addView(tvFiscal);

        TextView tvMark = new TextView(this);
        tvMark.setText("MARK: " + currentTableMark + "\nUID: " + currentTableUid);
        tvMark.setTextSize(11);
        layout.addView(tvMark);

        TextView tvQrLabel = new TextView(this);
        tvQrLabel.setText("\nΣάρωση για εγκυρότητα:");
        tvQrLabel.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        tvQrLabel.setTextSize(12);
        tvQrLabel.setTextColor(Color.BLACK);
        layout.addView(tvQrLabel);

        if (currentTableQrUrl != null && !currentTableQrUrl.isEmpty()) {
            ImageView ivQr = new ImageView(this);
                LinearLayout.LayoutParams ivParams = new LinearLayout.LayoutParams(400, 400);
            ivParams.gravity = Gravity.CENTER;
            ivParams.topMargin = 16;
            ivQr.setLayoutParams(ivParams);

            try {
                com.google.zxing.common.BitMatrix bitMatrix = new com.google.zxing.MultiFormatWriter()
                        .encode(currentTableQrUrl, com.google.zxing.BarcodeFormat.QR_CODE, 300, 300);

                int width = bitMatrix.getWidth();
                int height = bitMatrix.getHeight();
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.RGB_565);

                for (int x = 0; x < width; x++) {
                    for (int y = 0; y < height; y++) {
                        bmp.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                    }
                }
                ivQr.setImageBitmap(bmp);
                layout.addView(ivQr);
            } catch (Exception e) {
                TextView tvQrFallback = new TextView(this);
                tvQrFallback.setText(currentTableQrUrl);
                tvQrFallback.setTextSize(10);
                tvQrFallback.setTextColor(Color.BLUE);
                tvQrFallback.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                layout.addView(tvQrFallback);
            }
        } else {
            TextView tvNoQr = new TextView(this);
            tvNoQr.setText("(Το QR Code δεν είναι διαθέσιμο)");
            tvNoQr.setTextSize(10);
            tvNoQr.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            layout.addView(tvNoQr);
        }

        scrollView.addView(layout);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(scrollView)
                .setPositiveButton("ΚΛΕΙΣΙΜΟ", null)
                .setNeutralButton("ΕΚΤΥΠΩΣΗ", (d, w) -> printTableReport())
                .show();
    }

    private void printOpenTableReport() {
        DatabaseReference marksRef = FirebaseHelper.getReference("active_bills")
                .child(tableNumber).child("epsilon_marks");
        marksRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    Toast.makeText(TableOrderActivity.this,
                            "Δεν υπάρχουν αποθηκευμένα MARK", Toast.LENGTH_SHORT).show();
                    return;
                }

                List<Map<String, Object>> reportItems = new ArrayList<>();
                for (DataSnapshot markSnap : snapshot.getChildren()) {
                    Long mark = markSnap.child("mark").getValue(Long.class);
                    String qrUrl = markSnap.child("qrUrl").getValue(String.class);

                    Map<String, Object> item = new HashMap<>();
                    item.put("mark", mark);
                    item.put("qrUrl", qrUrl);
                    reportItems.add(item);
                }

                Map<String, Object> receiptData = new HashMap<>();
                receiptData.put("tableNumber", tableNumber);
                receiptData.put("type", "open_table_report");
                receiptData.put("marks", reportItems);
                receiptData.put("timestamp", System.currentTimeMillis());

                DatabaseReference receiptsRef = FirebaseHelper.getReference("receipts");
                receiptsRef.child(tableNumber + "_report").setValue(receiptData)
                        .addOnSuccessListener(aVoid ->
                                Toast.makeText(TableOrderActivity.this,
                                        "Η αναφορά στάλθηκε στον εκτυπωτή!", Toast.LENGTH_SHORT).show())
                        .addOnFailureListener(e ->
                                Toast.makeText(TableOrderActivity.this,
                                        "Σφάλμα αποστολής: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(TableOrderActivity.this,
                        "Σφάλμα ανάγνωσης Firebase", Toast.LENGTH_SHORT).show();
            }
        });
    }


    private void finishOrderWith86Slip() {
        if (cartItems.isEmpty()) {
            Toast.makeText(this, "Δεν υπάρχουν είδη", Toast.LENGTH_SHORT).show();
            return;
        }

        List<Map<String, Object>> itemsList = new ArrayList<>();
        for (CartItem item : cartItems) {
            Map<String, Object> map = new HashMap<>();
            map.put("name", item.name);
            map.put("quantity", item.quantity);
            map.put("price", item.price);
            itemsList.add(map);
        }

        boolean isAlreadyOpen = currentTableMark != null && !currentTableMark.isEmpty();

        EpsilonIntegrationHelper.sendOrderSlip86(this, tableNumber, itemsList, isAlreadyOpen,
                new EpsilonIntegrationHelper.CallbackWithResult<SendResponse>() {
                    @Override
                    public void onSuccess(SendResponse result) {
                        long mark = result.getMark();
                        String uid = result.getUid() != null ? result.getUid() : "";
                        String qrUrl = result.getQrCode() != null ? result.getQrCode() : "";

                        DatabaseReference orderRef = FirebaseHelper.getReference("active_bills").child(tableNumber);

                        Map<String, Object> lastFiscal = new HashMap<>();
                        lastFiscal.put("mark", String.valueOf(mark));
                        lastFiscal.put("uid", uid);
                        lastFiscal.put("qr", qrUrl);
                        lastFiscal.put("fiscal_time", new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
                        orderRef.child("last_fiscal_info").setValue(lastFiscal);

                        DatabaseReference marksRef = orderRef.child("epsilon_marks").push();
                        Map<String, Object> markData = new HashMap<>();
                        markData.put("mark", mark);
                        markData.put("uid", uid);
                        markData.put("qrUrl", qrUrl);
                        markData.put("timestamp", System.currentTimeMillis());
                        marksRef.setValue(markData);

                        Map<String, Object> statusUpdate = new HashMap<>();
                        statusUpdate.put("status", "printed");
                        orderRef.child("current_order").updateChildren(statusUpdate);

                        saveOrderToFirebase();

                        Toast.makeText(TableOrderActivity.this,
                                "Εκδόθηκε Δελτίο 8.6 με MARK: " + mark, Toast.LENGTH_LONG).show();
                        finish();
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(TableOrderActivity.this, "Σφάλμα έκδοσης 8.6: " + message,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void saveOrderToFirebase() {
        if (cartItems.isEmpty()) return;

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
        newOrder.put("status", "pending");

        DatabaseReference orderRef = FirebaseHelper.getReference("orders");
        String orderKey = orderRef.push().getKey();
        if (orderKey != null) {
            orderRef.child(orderKey).setValue(newOrder)
                    .addOnSuccessListener(aVoid ->
                            Log.d("TableOrder", "Η παραγγελία αποθηκεύτηκε με key: " + orderKey))
                    .addOnFailureListener(e ->
                            Log.e("TableOrder", "Σφάλμα αποθήκευσης παραγγελίας", e));
        }
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
                hadExistingOrders = false;

                if (snapshot.hasChild("last_fiscal_info")) {
                    DataSnapshot fiscalSnap = snapshot.child("last_fiscal_info");
                    currentTableMark = fiscalSnap.child("mark").getValue(String.class);
                    currentTableUid = fiscalSnap.child("uid").getValue(String.class);
                    currentTableQrUrl = fiscalSnap.child("qr").getValue(String.class);
                } else if (snapshot.hasChild("epsilon_marks")) {
                    for (DataSnapshot markSnap : snapshot.child("epsilon_marks").getChildren()) {
                        Object mVal = markSnap.child("mark").getValue();
                        if (mVal != null) currentTableMark = String.valueOf(mVal);
                        String uVal = markSnap.child("uid").getValue(String.class);
                        if (uVal != null) currentTableUid = uVal;
                        String qVal = markSnap.child("qrUrl").getValue(String.class);
                        if (qVal != null) currentTableQrUrl = qVal;
                    }
                }

                for (DataSnapshot orderSnapshot : snapshot.getChildren()) {

                    if (orderSnapshot.getKey().equals("last_fiscal_info") ||
                            orderSnapshot.getKey().equals("epsilon_marks") ||
                            orderSnapshot.getKey().equals("current_order")) continue;

                    Map<String, Object> order = (Map<String, Object>) orderSnapshot.getValue();
                    if (order != null && order.get("items") instanceof List) {
                        List<Map<String, Object>> items = (List<Map<String, Object>>) order.get("items");
                        for (Map<String, Object> item : items) {
                            String name = (String) item.get("name");

                            Object qtyObj = item.get("quantity");
                            int quantity = 0;
                            if (qtyObj instanceof Long) {
                                quantity = ((Long) qtyObj).intValue();
                            } else if (qtyObj instanceof Integer) {
                                quantity = (Integer) qtyObj;
                            }

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

                if (!cartItems.isEmpty()) {
                    hadExistingOrders = true;
                }

                cartAdapter.notifyDataSetChanged();
                updateTotal();
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
                    List<ProductItem> productList = new ArrayList<>();

                    for (DataSnapshot prodSnap : catSnap.getChildren()) {
                        String productName = prodSnap.getKey();
                        double price = 0.0;
                        double vatPercent = 13.0;

                        Object value = prodSnap.getValue();
                        if (value instanceof Map) {
                            Map<String, Object> productObj = (Map<String, Object>) value;
                            if (productObj.containsKey("name")) {
                                productName = (String) productObj.get("name");
                            }
                            Object priceObj = productObj.get("price");
                            if (priceObj instanceof Number) {
                                price = ((Number) priceObj).doubleValue();
                            }
                            if (productObj.containsKey("vatPercent")) {
                                Object vatObj = productObj.get("vatPercent");
                                vatPercent = vatObj instanceof Number ? ((Number) vatObj).doubleValue() : 13.0;
                            }
                        } else if (value instanceof Number) {
                            price = ((Number) value).doubleValue();
                        } else {
                            continue;
                        }
                        productList.add(new ProductItem(productName, price, vatPercent));
                    }
                    productsByCategory.put(category, productList);
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
        List<ProductItem> products = productsByCategory.get(category);
        if (products == null) return;
        productAdapter = new ProductAdapter(products, this::addProductToCart);
        rvProducts.setAdapter(productAdapter);
    }

    private void addProductToCart(ProductItem product) {
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
        updateTotal();
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
        newOrder.put("status", "pending");

        activeBillsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot child : snapshot.getChildren()) {
                    Object val = child.getValue();
                    if (val instanceof Map && ((Map<?, ?>) val).containsKey("items")) {
                        child.getRef().removeValue();
                    }
                }
                activeBillsRef.child("current_order").setValue(newOrder)
                        .addOnSuccessListener(aVoid -> {
                            Toast.makeText(TableOrderActivity.this, "Αποθηκεύτηκε", Toast.LENGTH_SHORT).show();
                            finish();
                        })
                        .addOnFailureListener(e -> Toast.makeText(TableOrderActivity.this, "Σφάλμα αποθήκευσης", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

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

    static class CartItem {
        String name; int quantity; double price; String comment; String orderId;
        CartItem(String name, int quantity, double price, String comment, String orderId) {
            this.name = name; this.quantity = quantity; this.price = price; this.comment = comment; this.orderId = orderId;
        }
    }
    static class ProductItem {
        String name;
        double price;
        double vatPercent;
        ProductItem(String n, double p, double v) { name=n; price=p; vatPercent=v; }
    }
    private void printTableReport() {
        new Thread(() -> {
            com.zcs.sdk.DriverManager driverManager = com.zcs.sdk.DriverManager.getInstance();
            com.zcs.sdk.Printer printer = driverManager.getPrinter();

            if (printer == null) {
                runOnUiThread(() -> Toast.makeText(TableOrderActivity.this, "Ο εκτυπωτής δεν είναι διαθέσιμος", Toast.LENGTH_SHORT).show());
                return;
            }

            int printStatus = printer.getPrinterStatus();
            if (printStatus != com.zcs.sdk.SdkResult.SDK_OK) {
                runOnUiThread(() -> Toast.makeText(TableOrderActivity.this, "Σφάλμα εκτυπωτή: " + printStatus, Toast.LENGTH_SHORT).show());
                return;
            }

            printText(printer, "ΑΝΑΦΟΡΑ ΑΝΟΙΚΤΟΥ ΤΡΑΠΕΖΙΟΥ", com.zcs.sdk.print.PrnTextStyle.BOLD, 24, android.text.Layout.Alignment.ALIGN_CENTER);
            printText(printer, "(ΔΕΛΤΙΟ ΠΑΡΑΓΓΕΛΙΑΣ 8.6)", com.zcs.sdk.print.PrnTextStyle.NORMAL, 20, android.text.Layout.Alignment.ALIGN_CENTER);
            printText(printer, "--------------------------------", com.zcs.sdk.print.PrnTextStyle.NORMAL, 20, android.text.Layout.Alignment.ALIGN_CENTER);

            String dateStr = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(new java.util.Date());
            printText(printer, "ΤΡΑΠΕΖΙ: " + tableNumber, com.zcs.sdk.print.PrnTextStyle.BOLD, 20, android.text.Layout.Alignment.ALIGN_NORMAL);
            printText(printer, "ΗΜΕΡ/ΝΙΑ: " + dateStr, com.zcs.sdk.print.PrnTextStyle.NORMAL, 20, android.text.Layout.Alignment.ALIGN_NORMAL);
            printText(printer, "--------------------------------", com.zcs.sdk.print.PrnTextStyle.NORMAL, 20, android.text.Layout.Alignment.ALIGN_CENTER);

            printText(printer, "ΠΕΡΙΓΡΑΦΗ          ΠΟΣ.    ΑΞΙΑ", com.zcs.sdk.print.PrnTextStyle.BOLD, 18, android.text.Layout.Alignment.ALIGN_NORMAL);

            double totalAmount = 0;
            for (CartItem item : cartItems) {
                double lineTotal = item.price * item.quantity;
                totalAmount += lineTotal;

                String line = String.format(java.util.Locale.getDefault(), "%-18s %3d %7.2f€",
                        item.name.length() > 18 ? item.name.substring(0, 18) : item.name,
                        item.quantity,
                        lineTotal);

                printText(printer, line, com.zcs.sdk.print.PrnTextStyle.NORMAL, 18, android.text.Layout.Alignment.ALIGN_NORMAL);
            }
            printText(printer, "--------------------------------", com.zcs.sdk.print.PrnTextStyle.NORMAL, 20, android.text.Layout.Alignment.ALIGN_CENTER);

            String totalStr = String.format(java.util.Locale.getDefault(), "ΣΥΝΟΛΟ: %.2f€", totalAmount);
            printText(printer, totalStr, com.zcs.sdk.print.PrnTextStyle.BOLD, 24, android.text.Layout.Alignment.ALIGN_OPPOSITE);
            printText(printer, "--------------------------------", com.zcs.sdk.print.PrnTextStyle.NORMAL, 20, android.text.Layout.Alignment.ALIGN_CENTER);

            printText(printer, "--- ΦΟΡΟΛΟΓΙΚΑ ΣΤΟΙΧΕΙΑ ΑΑΔΕ ---", com.zcs.sdk.print.PrnTextStyle.BOLD, 18, android.text.Layout.Alignment.ALIGN_CENTER);

            String safeMark = (currentTableMark != null && !currentTableMark.isEmpty()) ? currentTableMark : "ΕΚΚΡΕΜΕΙ (IN PROGRESS)";
            String safeUid = (currentTableUid != null && !currentTableUid.isEmpty()) ? currentTableUid : "-";

            printText(printer, "MARK: " + safeMark, com.zcs.sdk.print.PrnTextStyle.NORMAL, 18, android.text.Layout.Alignment.ALIGN_NORMAL);
            printText(printer, "UID: " + safeUid, com.zcs.sdk.print.PrnTextStyle.NORMAL, 16, android.text.Layout.Alignment.ALIGN_NORMAL);

            if (currentTableQrUrl != null && !currentTableQrUrl.isEmpty()) {
                printText(printer, " ", com.zcs.sdk.print.PrnTextStyle.NORMAL, 10, android.text.Layout.Alignment.ALIGN_CENTER);

                printer.setPrintAppendQRCode(currentTableQrUrl, 240, 240, android.text.Layout.Alignment.ALIGN_CENTER);
            } else {
                printText(printer, "(Το QR Code δεν είναι διαθέσιμο)", com.zcs.sdk.print.PrnTextStyle.NORMAL, 16, android.text.Layout.Alignment.ALIGN_CENTER);
            }

            printText(printer, " \n \n \n", com.zcs.sdk.print.PrnTextStyle.NORMAL, 20, android.text.Layout.Alignment.ALIGN_NORMAL);

            int startRes = printer.setPrintStart();
            if (startRes == com.zcs.sdk.SdkResult.SDK_OK) {
                runOnUiThread(() -> Toast.makeText(TableOrderActivity.this, "Η εκτύπωση ολοκληρώθηκε", Toast.LENGTH_SHORT).show());
            } else {
                runOnUiThread(() -> Toast.makeText(TableOrderActivity.this, "Αποτυχία εκτύπωσης: " + startRes, Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void printText(com.zcs.sdk.Printer printer, String text, com.zcs.sdk.print.PrnTextStyle style, int textSize, android.text.Layout.Alignment align) {
        com.zcs.sdk.print.PrnStrFormat format = new com.zcs.sdk.print.PrnStrFormat();
        format.setTextSize(textSize);
        format.setStyle(style);
        format.setAli(align);
        printer.setPrintAppendString(text, format);
    }
}