package com.ads.paragelia;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.firebase.database.*;
import java.util.*;

public class MenuRepository {
    private static MenuRepository instance;
    private List<String> categoryList = new ArrayList<>();
    private Map<String, List<ProductSelectionBottomSheet.ProductItem>> productsByCategoryFull = new HashMap<>();
    private List<ProductSelectionBottomSheet.ProductItem> allProducts = new ArrayList<>();
    private boolean isLoaded = false;
    private Map<String, String> categoryTargets = new HashMap<>();   // φόρτωση των targets

    public interface OnMenuLoadedListener {
        void onMenuLoaded();
    }

    private MenuRepository() {}

    public static synchronized MenuRepository getInstance() {
        if (instance == null) {
            instance = new MenuRepository();
        }
        return instance;
    }

    public List<String> getCategories() { return categoryList; }
    public Map<String, List<ProductSelectionBottomSheet.ProductItem>> getProductsByCategory() { return productsByCategoryFull; }
    public List<ProductSelectionBottomSheet.ProductItem> getAllProducts() { return allProducts; }
    public boolean isLoaded() { return isLoaded; }

    public String getCategoryPrinterTarget(String category) {
        return categoryTargets.getOrDefault(category, "RECEIPT");
    }

    // Φόρτωση από cache ή Firebase
    public void loadMenu(Context context, OnMenuLoadedListener listener) {
        Map<String, Object> cached = MenuCache.loadProducts(context);
        if (cached != null) {
            parseProductsMap(cached);
            if (listener != null) listener.onMenuLoaded();
            fetchFromFirebase(context, null);
        } else {
            fetchFromFirebase(context, listener);
        }
    }

    private void fetchFromFirebase(Context context, OnMenuLoadedListener listener) {
        DatabaseReference productsRef = FirebaseHelper.getReference("products");
        productsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Map<String, Object> cacheMap = new HashMap<>();
                for (DataSnapshot catSnap : snapshot.getChildren()) {
                    cacheMap.put(catSnap.getKey(), catSnap.getValue());
                }
                MenuCache.saveProducts(context, cacheMap);
                parseProductsMap(cacheMap);
                if (listener != null) listener.onMenuLoaded();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void parseProductsMap(Map<String, Object> productsMap) {
        categoryList.clear();
        productsByCategoryFull.clear();
        allProducts.clear();
        categoryTargets.clear();

        for (Map.Entry<String, Object> entry : productsMap.entrySet()) {
            String category = entry.getKey();
            categoryList.add(category);
            Map<String, Object> catMap = (Map<String, Object>) entry.getValue();

            if (catMap.containsKey("_printerTarget")) {
                categoryTargets.put(category, (String) catMap.get("_printerTarget"));
            }

            List<ProductSelectionBottomSheet.ProductItem> productList = new ArrayList<>();

            for (Map.Entry<String, Object> prodEntry : catMap.entrySet()) {
                String productKey = prodEntry.getKey();
                if (productKey.startsWith("_")) continue;

                Object value = prodEntry.getValue();

                String productName = productKey;
                double price = 0.0;
                double vatPercent = 13.0;
                List<ProductSelectionBottomSheet.Ingredient> ingredients = new ArrayList<>();
                List<ProductSelectionBottomSheet.Addon> addons = new ArrayList<>();
                List<ProductSelectionBottomSheet.Variant> variants = new ArrayList<>();

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
                    // ingredients
                    Object ingredientsObj = productObj.get("ingredients");
                    if (ingredientsObj instanceof Map) {
                        Map<String, Object> ingsMap = (Map<String, Object>) ingredientsObj;
                        for (Map.Entry<String, Object> ingEntry : ingsMap.entrySet()) {
                            Object ingValue = ingEntry.getValue();
                            if (ingValue instanceof Map) {
                                Map<String, Object> ingMap = (Map<String, Object>) ingValue;
                                String ingName = (String) ingMap.get("name");
                                if (ingName != null) {
                                    ingredients.add(new ProductSelectionBottomSheet.Ingredient(ingName));
                                }
                            }
                        }
                    }
                    // addons
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
                                    addons.add(new ProductSelectionBottomSheet.Addon(addName, addPrice));
                                }
                            }
                        }
                    }
                    // variants
                    Object variantsObj = productObj.get("variants");
                    if (variantsObj instanceof Map) {
                        Map<String, Object> varMap = (Map<String, Object>) variantsObj;
                        for (Map.Entry<String, Object> varEntry : varMap.entrySet()) {
                            Object varValue = varEntry.getValue();
                            if (varValue instanceof Map) {
                                Map<String, Object> varData = (Map<String, Object>) varValue;
                                String varName = (String) varData.get("name");
                                Object varPriceObj = varData.get("price");
                                double varPrice = (varPriceObj instanceof Number) ? ((Number) varPriceObj).doubleValue() : 0.0;
                                if (varName != null) {
                                    variants.add(new ProductSelectionBottomSheet.Variant(varName, varPrice));
                                }
                            }
                        }
                    }
                } else if (value instanceof Number) {
                    price = ((Number) value).doubleValue();
                } else {
                    continue;
                }

                ProductSelectionBottomSheet.ProductItem item = new ProductSelectionBottomSheet.ProductItem(
                        productName, price, vatPercent, ingredients, addons);
                item.category = category;
                item.variants = variants;   // αποθήκευση παραλλαγών
                productList.add(item);
                allProducts.add(item);
            }
            productsByCategoryFull.put(category, productList);
        }
        isLoaded = true;
    }

    public void refresh(Context context, OnMenuLoadedListener listener) {
        isLoaded = false;
        MenuCache.clear(context);
        fetchFromFirebase(context, listener);
    }
}