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
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SelectProductActivity extends BaseActivity {

    private RecyclerView categoryRecycler, productRecycler;
    private TextView tvCategoryTitle;
    private EditText etQuantity;
    private Button btnAddProduct;
    private DatabaseReference productsRef;
    private List<String> categoryList = new ArrayList<>();
    private List<String> productList = new ArrayList<>();
    private Map<String, Object> currentProductPrices; // Τιμές ως Object (μπορεί Long ή Double)
    private String selectedCategory = null;
    private String selectedProduct = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_select_product);

        categoryRecycler = findViewById(R.id.categoryRecyclerView);
        productRecycler = findViewById(R.id.productRecyclerView);
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle);
        etQuantity = findViewById(R.id.etQuantity);
        btnAddProduct = findViewById(R.id.btnAddProduct);

        categoryRecycler.setLayoutManager(new LinearLayoutManager(this));
        productRecycler.setLayoutManager(new LinearLayoutManager(this));

        productsRef = FirebaseDatabase.getInstance().getReference("products");

        productsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                categoryList.clear();
                for (DataSnapshot catSnap : snapshot.getChildren()) {
                    categoryList.add(catSnap.getKey());
                }
                categoryRecycler.setAdapter(new CategoryAdapter(categoryList));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(SelectProductActivity.this, "Σφάλμα φόρτωσης κατηγοριών", Toast.LENGTH_SHORT).show();
            }
        });

        btnAddProduct.setOnClickListener(v -> {
            if (selectedProduct == null) {
                Toast.makeText(this, "Επέλεξε πρώτα προϊόν", Toast.LENGTH_SHORT).show();
                return;
            }
            String quantityStr = etQuantity.getText().toString().trim();
            if (quantityStr.isEmpty()) {
                Toast.makeText(this, "Βάλε ποσότητα", Toast.LENGTH_SHORT).show();
                return;
            }
            int quantity = Integer.parseInt(quantityStr);
            // Ασφαλής μετατροπή τιμής από Object σε double
            double price = getPriceAsDouble(currentProductPrices.get(selectedProduct));
            Intent resultIntent = new Intent();
            resultIntent.putExtra("product_name", selectedProduct);
            resultIntent.putExtra("quantity", quantity);
            resultIntent.putExtra("price", price);
            setResult(RESULT_OK, resultIntent);
            finish();
        });
    }

    // Βοηθητική μέθοδος για μετατροπή Object (Long/Double) σε double
    private double getPriceAsDouble(Object value) {
        if (value instanceof Double) {
            return (Double) value;
        } else if (value instanceof Long) {
            return ((Long) value).doubleValue();
        } else if (value instanceof Integer) {
            return ((Integer) value).doubleValue();
        } else if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.ViewHolder> {
        private List<String> categories;
        CategoryAdapter(List<String> categories) { this.categories = categories; }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String cat = categories.get(position);
            holder.textView.setText(cat);
            holder.itemView.setOnClickListener(v -> {
                selectedCategory = cat;
                tvCategoryTitle.setText("Κατηγορία: " + cat);
                loadProducts(cat);
            });
        }

        @Override
        public int getItemCount() { return categories.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textView;
            ViewHolder(View itemView) { super(itemView); textView = itemView.findViewById(android.R.id.text1); }
        }
    }

    private void loadProducts(String category) {
        productsRef.child(category).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                productList.clear();
                currentProductPrices = (Map<String, Object>) snapshot.getValue();
                if (currentProductPrices != null) {
                    productList.addAll(currentProductPrices.keySet());
                }
                productRecycler.setAdapter(new ProductAdapter(productList));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) { }
        });
    }

    private class ProductAdapter extends RecyclerView.Adapter<ProductAdapter.ViewHolder> {
        private List<String> products;
        ProductAdapter(List<String> products) { this.products = products; }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String prod = products.get(position);
            // Ασφαλής ανάκτηση τιμής
            Object priceObj = currentProductPrices.get(prod);
            double price = getPriceAsDouble(priceObj);
            holder.textView.setText(prod + " - €" + String.format("%.2f", price));
            holder.itemView.setOnClickListener(v -> {
                selectedProduct = prod;
                // Optional: visual feedback
                Toast.makeText(SelectProductActivity.this, "Επιλέχθηκε: " + prod, Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public int getItemCount() { return products.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textView;
            ViewHolder(View itemView) { super(itemView); textView = itemView.findViewById(android.R.id.text1); }
        }
    }
}