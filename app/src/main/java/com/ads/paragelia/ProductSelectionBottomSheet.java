package com.ads.paragelia;

import static android.content.Context.MODE_PRIVATE;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.database.*;
import java.util.*;
import android.content.Context;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;

public class ProductSelectionBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_TABLE_NUMBER = "table_number";
    private String tableNumber;
    private DatabaseReference productsRef;
    private List<String> categoryList = new ArrayList<>();
    private Map<String, List<ProductItem>> productsByCategoryFull = new HashMap<>();
    private List<ProductItem> allProducts = new ArrayList<>();
    private TabLayout tabCategories;
    private RecyclerView rvProducts, rvSelectedItems;
    private Button btnSubmitOnly;
    private Button btnSubmitAndPrint;
    private ProductAdapter productAdapter;
    private SelectedItemsAdapter selectedAdapter;
    private List<OrderItem> selectedItems = new ArrayList<>();
    private String currentCategory;
    private DatabaseReference tableRef;
    private EditText etSearch;

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

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tableNumber = getArguments().getString(ARG_TABLE_NUMBER);
        productsRef = FirebaseDatabase.getInstance().getReference("products");
        tableRef = getOrderRef();
        tabCategories = view.findViewById(R.id.tabCategories);
        rvProducts = view.findViewById(R.id.rvProducts);
        rvSelectedItems = view.findViewById(R.id.rvSelectedItems);
        btnSubmitOnly = view.findViewById(R.id.btnSubmitOnly);
        btnSubmitAndPrint = view.findViewById(R.id.btnSubmitAndPrint);
        etSearch = view.findViewById(R.id.etSearch);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        btnSubmitOnly.setOnClickListener(v -> submitOrder(false));
        btnSubmitAndPrint.setOnClickListener(v -> submitOrder(true));
        rvProducts.setLayoutManager(new LinearLayoutManager(getContext()));
        rvSelectedItems.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));

        btnSubmitOnly.setOnLongClickListener(v -> {
            showOrderPreview();
            return true;
        });

        btnSubmitAndPrint.setOnLongClickListener(v -> {
            showOrderPreview();
            return true;
        });
        // Πρώτα δημιουργούμε τον adapter
        selectedAdapter = new SelectedItemsAdapter(selectedItems, new SelectedItemsAdapter.OnItemActionListener() {
            @Override
            public void onDecrease(int position) {
                OrderItem item = selectedItems.get(position);
                if (item.quantity > 1) {
                    item.quantity--;
                    selectedAdapter.notifyItemChanged(position);
                } else {
                    selectedItems.remove(position);
                    selectedAdapter.notifyItemRemoved(position);
                }
                updateButtonsState();
                updateFirebaseOrder();
            }

            @Override
            public void onRemove(int position) {
                selectedItems.remove(position);
                selectedAdapter.notifyItemRemoved(position);
                updateButtonsState();
                updateFirebaseOrder();
            }
        });        rvSelectedItems.setAdapter(selectedAdapter);

        // Τώρα μπορούμε να φορτώσουμε τα υπάρχοντα δεδομένα
        loadExistingOrder();

        loadProductsWithCache();
        updateButtonsState();
        rvProducts.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                hideKeyboard();
            }
            return false;
        });

        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hideKeyboard();
            }
            return false;
        });
     }
    private void showOrderPreview() {
        if (selectedItems.isEmpty()) {
            Toast.makeText(getContext(), "Το καλάθι είναι άδειο", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder sb = new StringBuilder();
        double total = 0;
        for (OrderItem item : selectedItems) {
            sb.append(item.name).append(" x").append(item.quantity);
            if (item.comment != null && !item.comment.isEmpty()) {
                sb.append(" (").append(item.comment).append(")");
            }
            sb.append("\n");
            total += item.price * item.quantity;
        }
        sb.append("\nΣύνολο: €").append(String.format("%.2f", total));

        new AlertDialog.Builder(getContext())
                .setTitle("Προεπισκόπηση Παραγγελίας")
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }
    private void updateButtonsState() {
        boolean hasItems = !selectedItems.isEmpty();
        btnSubmitOnly.setEnabled(hasItems);
        btnSubmitAndPrint.setEnabled(hasItems);
        float alpha = hasItems ? 1.0f : 0.5f;
        btnSubmitOnly.setAlpha(alpha);
        btnSubmitAndPrint.setAlpha(alpha);
    }

    private void loadCategoriesAndProducts() {
        productsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                categoryList.clear();
                productsByCategoryFull.clear();
                allProducts.clear();

                for (DataSnapshot catSnap : snapshot.getChildren()) {
                    String category = catSnap.getKey();
                    categoryList.add(category);

                    List<ProductItem> productList = new ArrayList<>();

                    for (DataSnapshot prodSnap : catSnap.getChildren()) {
                        // Μέσα στο for (DataSnapshot prodSnap : catSnap.getChildren())
                        String productName = prodSnap.getKey();
                        double price = 0.0;
                        List<Ingredient> ingredients = new ArrayList<>();
                        List<Addon> addons = new ArrayList<>();

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
                            // Ανάγνωση συστατικών
                            Object ingredientsObj = productObj.get("ingredients");
                            if (ingredientsObj instanceof Map) {
                                Map<String, Object> ingsMap = (Map<String, Object>) ingredientsObj;
                                for (Map.Entry<String, Object> entry : ingsMap.entrySet()) {
                                    Object ingValue = entry.getValue();
                                    if (ingValue instanceof Map) {
                                        Map<String, Object> ingMap = (Map<String, Object>) ingValue;
                                        String ingName = (String) ingMap.get("name");
                                        if (ingName != null) {
                                            ingredients.add(new Ingredient(ingName));
                                        }
                                    }
                                }
                            }
                            // Ανάγνωση έξτρα υλικών (addons)
                            Object addonsObj = productObj.get("addons");
                            if (addonsObj instanceof Map) {
                                Map<String, Object> addMap = (Map<String, Object>) addonsObj;
                                for (Map.Entry<String, Object> entry : addMap.entrySet()) {
                                    Object addValue = entry.getValue();
                                    if (addValue instanceof Map) {
                                        Map<String, Object> addItem = (Map<String, Object>) addValue;
                                        String addName = (String) addItem.get("name");
                                        double addPrice = 0.0;
                                        Object addPriceObj = addItem.get("price");
                                        if (addPriceObj instanceof Number) {
                                            addPrice = ((Number) addPriceObj).doubleValue();
                                        }
                                        if (addName != null) {
                                            addons.add(new Addon(addName, addPrice));
                                        }
                                    }
                                }
                            }
                        } else if (value instanceof Number) {
                            price = ((Number) value).doubleValue();
                        } else {
                            continue;
                        }

                        ProductItem item = new ProductItem(productName, price, ingredients, addons);
                        productList.add(item);
                        allProducts.add(item);
                    }

                    productsByCategoryFull.put(category, productList);
                }
                setupTabs();
                if (!categoryList.isEmpty()) {
                    currentCategory = categoryList.get(0);
                    showProductsForCategory(currentCategory);
                }

// Αποθήκευση στην cache
                Map<String, Object> cacheMap = new HashMap<>();
                for (DataSnapshot catSnap : snapshot.getChildren()) {
                    cacheMap.put(catSnap.getKey(), catSnap.getValue());
                }
                MenuCache.saveProducts(getContext(), cacheMap);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }
    private void setupTabs() {
        for (String cat : categoryList) {
            tabCategories.addTab(tabCategories.newTab().setText(cat));
        }
        tabCategories.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                currentCategory = categoryList.get(tab.getPosition());
                etSearch.setText("");
                showProductsForCategory(currentCategory);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }
    private void addProductQuick(ProductItem product) {
        addProductToCart(product, 1, "");
    }
    private void showProductsForCategory(String category) {
        List<ProductItem> products = productsByCategoryFull.get(category);
        if (products == null) return;

        productAdapter = new ProductAdapter(products, this::addProduct, this);
        rvProducts.setAdapter(productAdapter);
    }
    private void applyFilter(String query) {
        String lowerQuery = query.toLowerCase().trim();
        if (lowerQuery.isEmpty()) {
            showProductsForCategory(currentCategory);
            return;
        }

        List<ProductItem> filtered = new ArrayList<>();
        for (ProductItem item : allProducts) {
            if (item.name.toLowerCase().contains(lowerQuery)) {
                filtered.add(item);
            }
        }

        productAdapter = new ProductAdapter(filtered, this::addProduct, this);
        rvProducts.setAdapter(productAdapter);
    }

    private void addProduct(ProductItem product) {
        if (!product.ingredients.isEmpty()) {
            showIngredientsRemovalDialog(product);
        } else if (!product.addons.isEmpty()) {
            showAddonsSelectionDialog(product);
        } else {
            showQuantityCommentDialog(product, new ArrayList<>(), new ArrayList<>());
        }
    }
    private void showAddonsSelectionDialog(ProductItem product) {
        showAddonsSelectionDialog(product, new ArrayList<>());
    }
    private void showAddonsSelectionDialog(ProductItem product, List<Ingredient> removedIngredients) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Έξτρα υλικά - " + product.name);

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        List<Addon> addons = product.addons;
        CheckBox[] checkBoxes = new CheckBox[addons.size()];

        for (int i = 0; i < addons.size(); i++) {
            Addon add = addons.get(i);
            CheckBox cb = new CheckBox(getContext());
            String label = add.name;
            if (add.price > 0) label += " (+€" + String.format("%.2f", add.price) + ")";
            cb.setText(label);
            layout.addView(cb);
            checkBoxes[i] = cb;
        }

        builder.setView(layout);
        builder.setPositiveButton("Συνέχεια", (dialog, which) -> {
            List<Addon> selectedAddons = new ArrayList<>();
            for (int i = 0; i < checkBoxes.length; i++) {
                if (checkBoxes[i].isChecked()) {
                    selectedAddons.add(addons.get(i));
                }
            }
            showQuantityCommentDialog(product, removedIngredients, selectedAddons);
        });
        builder.setNegativeButton("Ακύρωση", null);
        builder.show();
    }
    private void showIngredientsRemovalDialog(ProductItem product) {
        showIngredientsRemovalDialog(product, new ArrayList<>());
    }

    private void showIngredientsRemovalDialog(ProductItem product, List<Addon> selectedAddons) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Αφαίρεση συστατικών - " + product.name);

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        List<Ingredient> ingredients = product.ingredients;
        CheckBox[] checkBoxes = new CheckBox[ingredients.size()];

        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ing = ingredients.get(i);
            CheckBox cb = new CheckBox(getContext());
            cb.setText(ing.name);
            cb.setChecked(true);  // προεπιλεγμένο – σημαίνει ότι ΔΕΝ έχει αφαιρεθεί
            layout.addView(cb);
            checkBoxes[i] = cb;
        }

        builder.setView(layout);
        builder.setPositiveButton("Συνέχεια", (dialog, which) -> {
            List<Ingredient> removed = new ArrayList<>();
            for (int i = 0; i < checkBoxes.length; i++) {
                if (!checkBoxes[i].isChecked()) {
                    removed.add(ingredients.get(i));
                }
            }
            // Αν το προϊόν έχει και addons, πηγαίνουμε στο dialog επιλογής τους
            if (!product.addons.isEmpty()) {
                showAddonsSelectionDialog(product, removed);
            } else {
                showQuantityCommentDialog(product, removed, selectedAddons);
            }
        });
        builder.setNegativeButton("Ακύρωση", null);
        builder.show();
    }
    private void showQuantityCommentDialog(ProductItem product, List<Ingredient> removedIngredients, List<Addon> selectedAddons) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Προσθήκη: " + product.name);

        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_add_product, null);
        EditText etQuantity = dialogView.findViewById(R.id.etQuantity);
        EditText etComment = dialogView.findViewById(R.id.etComment);
        etQuantity.setText("1");

        LinearLayout root = (LinearLayout) dialogView;
        if (!removedIngredients.isEmpty()) {
            TextView tvRemoved = new TextView(getContext());
            StringBuilder sb = new StringBuilder("Χωρίς: ");
            for (Ingredient ing : removedIngredients) sb.append(ing.name).append(" ");
            tvRemoved.setText(sb.toString());
            tvRemoved.setPadding(0, 0, 0, 8);
            root.addView(tvRemoved, 0);
        }
        if (!selectedAddons.isEmpty()) {
            TextView tvAddons = new TextView(getContext());
            StringBuilder sb = new StringBuilder("Έξτρα: ");
            double extraCost = 0;
            for (Addon add : selectedAddons) {
                sb.append(add.name).append(" ");
                extraCost += add.price;
            }
            tvAddons.setText(sb.toString());
            tvAddons.setPadding(0, 0, 0, 8);
            root.addView(tvAddons, 0);
        }

        builder.setView(dialogView);
        double finalBasePrice = product.price;
        double extraTotal = 0;
        for (Addon add : selectedAddons) extraTotal += add.price;
        double finalPrice = finalBasePrice + extraTotal;

        builder.setPositiveButton("Προσθήκη", (dialog, which) -> {
            String qtyStr = etQuantity.getText().toString().trim();
            int quantity = 1;
            try {
                quantity = Integer.parseInt(qtyStr);
                if (quantity <= 0) quantity = 1;
            } catch (NumberFormatException ignored) {}

            String comment = etComment.getText().toString().trim();
            StringBuilder fullComment = new StringBuilder(comment);
            if (!removedIngredients.isEmpty()) {
                if (fullComment.length() > 0) fullComment.append(" | ");
                fullComment.append("Χωρίς: ");
                for (int i = 0; i < removedIngredients.size(); i++) {
                    if (i > 0) fullComment.append(", ");
                    fullComment.append(removedIngredients.get(i).name);
                }
            }
            if (!selectedAddons.isEmpty()) {
                if (fullComment.length() > 0) fullComment.append(" | ");
                fullComment.append("Έξτρα: ");
                for (int i = 0; i < selectedAddons.size(); i++) {
                    if (i > 0) fullComment.append(", ");
                    fullComment.append(selectedAddons.get(i).name);
                }
            }

            ProductItem finalProduct = new ProductItem(product.name, finalPrice);
            addProductToCart(finalProduct, quantity, fullComment.toString());
        });
        builder.setNegativeButton("Ακύρωση", null);
        builder.show();
    }
    private void addProductToCart(ProductItem product, int quantity, String comment) {
        boolean found = false;
        for (OrderItem item : selectedItems) {
            if (item.name.equals(product.name) &&
                    ((item.comment == null && comment.isEmpty()) ||
                            (item.comment != null && item.comment.equals(comment)))) {
                item.quantity += quantity;
                found = true;
                break;
            }
        }
        if (!found) {
            selectedItems.add(new OrderItem(product.name, quantity, product.price, comment));
        }
        selectedAdapter.notifyDataSetChanged();
        updateFirebaseOrder();
        Toast.makeText(getContext(), "Προστέθηκε: " + product.name, Toast.LENGTH_SHORT).show();
        updateButtonsState();
    }
    private void addProductWithComment(ProductItem product, int quantity, String comment) {
        boolean found = false;
        for (OrderItem item : selectedItems) {
            // Έλεγχος ίδιου ονόματος και ίδιου σχολίου
            if (item.name.equals(product.name) &&
                    ((item.comment == null && comment.isEmpty()) ||
                            (item.comment != null && item.comment.equals(comment)))) {
                item.quantity += quantity;
                found = true;
                break;
            }
        }
        if (!found) {
            selectedItems.add(new OrderItem(product.name, quantity, product.price, comment));
        }
        selectedAdapter.notifyDataSetChanged();
        updateFirebaseOrder();
        Toast.makeText(getContext(), "Προστέθηκε: " + product.name, Toast.LENGTH_SHORT).show();
    }
    private void submitOrder(boolean printReceipt) {
        if (selectedItems.isEmpty()) {
            Toast.makeText(getContext(), "Δεν προστέθηκε κανένα προϊόν", Toast.LENGTH_SHORT).show();
            return;
        }

        List<Map<String, Object>> itemsList = new ArrayList<>();
        for (OrderItem item : selectedItems) {
            Map<String, Object> map = new HashMap<>();
            map.put("name", item.name);
            map.put("quantity", item.quantity);
            map.put("price", item.price);
            map.put("comment", item.comment);
            itemsList.add(map);
        }

        Map<String, Object> orderUpdate = new HashMap<>();
        orderUpdate.put("items", itemsList);
        orderUpdate.put("timestamp", System.currentTimeMillis());

        if (isTakeAway()) {
            orderUpdate.put("orderNumber", tableNumber);
        } else {
            orderUpdate.put("tableNumber", Integer.parseInt(tableNumber));
        }

        // Το status εξαρτάται από το κουμπί (για να έχουμε πορτοκαλί ή λευκό)
        String status = printReceipt ? "printed" : "ordered";
        orderUpdate.put("status", status);

        tableRef.child("current_order").setValue(orderUpdate)
                .addOnSuccessListener(aVoid -> {
                    String details = buildOrderSummary(selectedItems);
                    saveToHistory(HistoryEntry.TYPE_ORDER_COMPLETED, tableNumber, 0.0, null, details);

                    // ΠΑΝΤΑ εκτύπωση (ανεξάρτητα από το κουμπί)
                    sendToPrinter(tableNumber, itemsList);
                    Toast.makeText(getContext(), "Η παραγγελία στάλθηκε και εκτυπώνεται!", Toast.LENGTH_SHORT).show();

                    dismiss();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(getContext(), "Σφάλμα αποθήκευσης: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void sendToPrinter(String tableNumber, List<Map<String, Object>> items) {
        // Δημιουργούμε ένα αντικείμενο receipt με τα απαραίτητα δεδομένα
        Map<String, Object> receiptData = new HashMap<>();
        receiptData.put("tableNumber", tableNumber);
        receiptData.put("items", items);
        receiptData.put("timestamp", System.currentTimeMillis());
        receiptData.put("type", "temporary"); // προσωρινή απόδειξη

        DatabaseReference receiptsRef = FirebaseDatabase.getInstance().getReference("receipts");
        receiptsRef.child(tableNumber).setValue(receiptData);
    }
    // ----- Adapters -----
    static class ProductItem {
        String name;
        double price;
        List<Ingredient> ingredients;
        List<Addon> addons;

        ProductItem(String n, double p) {
            this.name = n;
            this.price = p;
            this.ingredients = new ArrayList<>();
            this.addons = new ArrayList<>();
        }

        ProductItem(String n, double p, List<Ingredient> ingredients, List<Addon> addons) {
            this.name = n;
            this.price = p;
            this.ingredients = ingredients;
            this.addons = addons;
        }
    }
    static class OrderItem {
        String name;
        int quantity;
        double price;
        String comment;  // προσθήκη

        OrderItem(String n, int q, double p) {
            this.name = n;
            this.quantity = q;
            this.price = p;
            this.comment = ""; // default κενό
        }

        OrderItem(String n, int q, double p, String c) {
            this.name = n;
            this.quantity = q;
            this.price = p;
            this.comment = c;
        }
    }
    static class ProductAdapter extends RecyclerView.Adapter<ProductAdapter.ViewHolder> {
        private List<ProductItem> products;
        private OnProductClickListener listener;
        private ProductSelectionBottomSheet parent;

        interface OnProductClickListener {
            void onProductClick(ProductItem product);
        }

        ProductAdapter(List<ProductItem> products, OnProductClickListener listener, ProductSelectionBottomSheet parent) {
            this.products = products;
            this.listener = listener;
            this.parent = parent;
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

            // Απλό κλικ → κανονική ροή
            holder.itemView.setOnClickListener(v -> listener.onProductClick(p));

            // Παρατεταμένο πάτημα → γρήγορη προσθήκη με 1 τεμάχιο
            holder.itemView.setOnLongClickListener(v -> {
                parent.addProductQuick(p);
                return true; // καταναλώνουμε το event
            });
        }

        @Override
        public int getItemCount() {
            return products.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            android.widget.TextView text1, text2;
            ViewHolder(View v) {
                super(v);
                text1 = v.findViewById(android.R.id.text1);
                text2 = v.findViewById(android.R.id.text2);
            }
        }
    }

    static class SelectedItemsAdapter extends RecyclerView.Adapter<SelectedItemsAdapter.ViewHolder> {
        private List<OrderItem> items;
        private OnItemActionListener listener;

        interface OnItemActionListener {
            void onDecrease(int position);
            void onRemove(int position);
        }

        SelectedItemsAdapter(List<OrderItem> items, OnItemActionListener listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_selected_product, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            OrderItem item = items.get(position);

            // --- ΕΔΩ ΕΙΝΑΙ Η ΑΛΛΑΓΗ ---
            String displayText = item.name + " x" + item.quantity;
            if (item.comment != null && !item.comment.isEmpty()) {
                displayText += " (" + item.comment + ")";
            }
            holder.tvInfo.setText(displayText);
            // --- ΤΕΛΟΣ ΑΛΛΑΓΗΣ ---

            holder.btnDecrease.setOnClickListener(v -> {
                if (listener != null) listener.onDecrease(holder.getAdapterPosition());
            });

            holder.btnRemove.setOnClickListener(v -> {
                if (listener != null) listener.onRemove(holder.getAdapterPosition());
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvInfo;
            Button btnDecrease, btnRemove;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvInfo = itemView.findViewById(R.id.tvSelectedItemInfo);
                btnDecrease = itemView.findViewById(R.id.btnDecrease);
                btnRemove = itemView.findViewById(R.id.btnRemove);
            }
        }
    }

    private void loadExistingOrder() {
        tableRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                selectedItems.clear();
                for (DataSnapshot orderSnap : snapshot.getChildren()) {
                    Map<String, Object> order = (Map<String, Object>) orderSnap.getValue();
                    if (order != null && order.containsKey("items")) {
                        List<Map<String, Object>> items = (List<Map<String, Object>>) order.get("items");
                        for (Map<String, Object> item : items) {
                            String name = (String) item.get("name");

                            // quantity
                            Object qtyObj = item.get("quantity");
                            int qty = 1;
                            if (qtyObj instanceof Number) qty = ((Number) qtyObj).intValue();

                            // price
                            Object priceObj = item.get("price");
                            double price = 0.0;
                            if (priceObj instanceof Number) price = ((Number) priceObj).doubleValue();

                            // comment
                            String comment = "";
                            Object commentObj = item.get("comment");
                            if (commentObj instanceof String) comment = (String) commentObj;

                            selectedItems.add(new OrderItem(name, qty, price, comment));
                        }
                    }
                }
                selectedAdapter.notifyDataSetChanged();
                updateButtonsState();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void updateFirebaseOrder() {
        List<Map<String, Object>> itemsList = new ArrayList<>();
        for (OrderItem item : selectedItems) {
            Map<String, Object> map = new HashMap<>();
            map.put("name", item.name);
            map.put("quantity", item.quantity);
            map.put("price", item.price);
            map.put("comment", item.comment);  // <-- προσθήκη
            itemsList.add(map);
        }

        Map<String, Object> orderUpdate = new HashMap<>();
        orderUpdate.put("items", itemsList);
        orderUpdate.put("timestamp", System.currentTimeMillis());
        if (isTakeAway()) {
            orderUpdate.put("orderNumber", tableNumber);
        } else {
            orderUpdate.put("tableNumber", Integer.parseInt(tableNumber));
        }orderUpdate.put("orderNumber", tableNumber);

        // Αποθηκεύουμε πάντα με το ίδιο key "current_order" ώστε να αντικαθίσταται
        tableRef.child("current_order").setValue(orderUpdate);
    }
    private void saveToHistory(String type, String tableNumber, double amount,
                               String paymentMethod, String details) {
        SharedPreferences prefs = getContext().getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        String deviceName = prefs.getString(SettingsActivity.KEY_DEVICE_NAME, "Άγνωστη συσκευή");

        DatabaseReference historyRef = FirebaseDatabase.getInstance().getReference("history");
        String id = historyRef.push().getKey();

        HistoryEntry entry = new HistoryEntry(type, tableNumber, amount, paymentMethod,
                deviceName, System.currentTimeMillis(), details);

        historyRef.child(id).setValue(entry);
    }
    private String buildOrderSummary(List<OrderItem> items) {
        StringBuilder sb = new StringBuilder();
        for (OrderItem item : items) {
            sb.append(item.name).append(" x").append(item.quantity);
            if (item.comment != null && !item.comment.isEmpty()) {
                sb.append(" (").append(item.comment).append(")");
            }
            sb.append(", ");
        }
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }
    static class Ingredient {
        String name;

        Ingredient() {}

        Ingredient(String name) {
            this.name = name;
        }
    }
    static class Addon {
        String name;
        double price;

        Addon() {}

        Addon(String name, double price) {
            this.name = name;
            this.price = price;
        }
    }
    private void hideKeyboard() {
        View focused = getDialog().getCurrentFocus();
        if (focused instanceof EditText) {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
    }
    private void loadProductsWithCache() {
        // Προσπάθεια φόρτωσης από cache
        Map<String, Object> cached = MenuCache.loadProducts(getContext());
        if (cached != null) {
            parseProductsMap(cached);
            Toast.makeText(getContext(), "Το μενού φορτώθηκε από τη μνήμη", Toast.LENGTH_SHORT).show();
        } else {
            // Αν δεν υπάρχει cache, κατέβασε από Firebase
            loadCategoriesAndProducts();
        }
    }

    private void parseProductsMap(Map<String, Object> productsMap) {
        categoryList.clear();
        productsByCategoryFull.clear();
        allProducts.clear();

        for (Map.Entry<String, Object> entry : productsMap.entrySet()) {
            String category = entry.getKey();
            categoryList.add(category);
            List<ProductItem> productList = new ArrayList<>();

            Map<String, Object> catMap = (Map<String, Object>) entry.getValue();
            for (Map.Entry<String, Object> prodEntry : catMap.entrySet()) {
                String productKey = prodEntry.getKey();
                Object value = prodEntry.getValue();

                String productName = productKey;
                double price = 0.0;
                List<Ingredient> ingredients = new ArrayList<>();
                List<Addon> addons = new ArrayList<>();

                if (value instanceof Map) {
                    Map<String, Object> productObj = (Map<String, Object>) value;
                    if (productObj.containsKey("name")) {
                        productName = (String) productObj.get("name");
                    }
                    Object priceObj = productObj.get("price");
                    if (priceObj instanceof Number) {
                        price = ((Number) priceObj).doubleValue();
                    }
                    // Ανάγνωση συστατικών
                    Object ingredientsObj = productObj.get("ingredients");
                    if (ingredientsObj instanceof Map) {
                        Map<String, Object> ingsMap = (Map<String, Object>) ingredientsObj;
                        for (Map.Entry<String, Object> ingEntry : ingsMap.entrySet()) {
                            Object ingValue = ingEntry.getValue();
                            if (ingValue instanceof Map) {
                                Map<String, Object> ingMap = (Map<String, Object>) ingValue;
                                String ingName = (String) ingMap.get("name");
                                if (ingName != null) {
                                    ingredients.add(new Ingredient(ingName));
                                }
                            }
                        }
                    }
                    // Ανάγνωση addons
                    Object addonsObj = productObj.get("addons");
                    if (addonsObj instanceof Map) {
                        Map<String, Object> addMap = (Map<String, Object>) addonsObj;
                        for (Map.Entry<String, Object> addEntry : addMap.entrySet()) {
                            Object addValue = addEntry.getValue();
                            if (addValue instanceof Map) {
                                Map<String, Object> addItem = (Map<String, Object>) addValue;
                                String addName = (String) addItem.get("name");
                                double addPrice = 0.0;
                                Object addPriceObj = addItem.get("price");
                                if (addPriceObj instanceof Number) {
                                    addPrice = ((Number) addPriceObj).doubleValue();
                                }
                                if (addName != null) {
                                    addons.add(new Addon(addName, addPrice));
                                }
                            }
                        }
                    }
                } else if (value instanceof Number) {
                    price = ((Number) value).doubleValue();
                } else {
                    continue;
                }

                ProductItem item = new ProductItem(productName, price, ingredients, addons);
                productList.add(item);
                allProducts.add(item);
            }
            productsByCategoryFull.put(category, productList);
        }

        setupTabs();
        if (!categoryList.isEmpty()) {
            currentCategory = categoryList.get(0);
            showProductsForCategory(currentCategory);
        }
    }
    private boolean isTakeAway() {
        return tableNumber.startsWith("TA-");
    }

    private DatabaseReference getOrderRef() {
        if (isTakeAway()) {
            return FirebaseDatabase.getInstance().getReference("takeaway_orders").child(tableNumber);
        } else {
            return FirebaseDatabase.getInstance().getReference("active_bills").child(tableNumber);
        }
    }
    public void setOnDismissListener(DialogInterface.OnDismissListener listener) {
        this.dismissListener = listener;
    }

    private DialogInterface.OnDismissListener dismissListener;

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        if (dismissListener != null) {
            dismissListener.onDismiss(dialog);
        }
    }
}