package com.ads.paragelia;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NewOrderActivity extends AppCompatActivity {

    private EditText editTableNumber;
    private LinearLayout itemsContainer;
    private Button btnAddItem, btnSubmit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_order);

        editTableNumber = findViewById(R.id.editTableNumber);
        itemsContainer = findViewById(R.id.itemsContainer);
        btnAddItem = findViewById(R.id.btnAddItem);
        btnSubmit = findViewById(R.id.btnSubmit);

        // --- ΝΕΟΣ ΚΩΔΙΚΑΣ: Λήψη του αριθμού τραπεζιού από το Intent ---
        String tableNumber = getIntent().getStringExtra("EXTRA_TABLE_NUMBER");
        if (tableNumber != null && !tableNumber.trim().isEmpty()) {
            editTableNumber.setText(tableNumber);
            // Κλειδώνουμε το πεδίο ώστε ο σερβιτόρος να μην το αλλάξει κατά λάθος
            // αφού θέλει να προσθέσει είδη σε ήδη υπάρχον τραπέζι
            editTableNumber.setEnabled(false);
        }
        // ---------------------------------------------------------------

        btnAddItem.setOnClickListener(v -> addItemRow());
        btnSubmit.setOnClickListener(v -> submitOrder());

        // Προσθήκη αρχικής κενής γραμμής
        addItemRow();
    }

    private void addItemRow() {
        View itemRow = LayoutInflater.from(this).inflate(R.layout.item_order_row, itemsContainer, false);
        ImageButton btnRemove = itemRow.findViewById(R.id.btnRemove);
        btnRemove.setOnClickListener(v -> itemsContainer.removeView(itemRow));
        itemsContainer.addView(itemRow);
    }

    private void submitOrder() {
        String tableNumberStr = editTableNumber.getText().toString().trim();
        if (tableNumberStr.isEmpty()) {
            Toast.makeText(this, "Εισάγετε αριθμό τραπεζιού", Toast.LENGTH_SHORT).show();
            return;
        }
        int tableNumber = Integer.parseInt(tableNumberStr);

        // Συλλογή προϊόντων
        List<Map<String, Object>> itemsList = new ArrayList<>();
        for (int i = 0; i < itemsContainer.getChildCount(); i++) {
            View row = itemsContainer.getChildAt(i);
            EditText editItemName = row.findViewById(R.id.editItemName);
            EditText editQuantity = row.findViewById(R.id.editQuantity);
            String name = editItemName.getText().toString().trim();
            String quantityStr = editQuantity.getText().toString().trim();
            if (name.isEmpty() || quantityStr.isEmpty()) {
                Toast.makeText(this, "Συμπληρώστε όλα τα πεδία προϊόντων", Toast.LENGTH_SHORT).show();
                return;
            }
            int quantity = Integer.parseInt(quantityStr);
            Map<String, Object> item = new HashMap<>();
            item.put("name", name);
            item.put("quantity", quantity);
            itemsList.add(item);
        }

        if (itemsList.isEmpty()) {
            Toast.makeText(this, "Προσθέστε τουλάχιστον ένα προϊόν", Toast.LENGTH_SHORT).show();
            return;
        }

        // Δημιουργία αντικειμένου παραγγελίας
        Map<String, Object> order = new HashMap<>();
        order.put("tableNumber", tableNumber);
        order.put("items", itemsList);
        order.put("timestamp", System.currentTimeMillis());
        order.put("status", "pending");
        order.put("deviceRole", "client");

        // Αποστολή στο Firebase (default instance)
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        DatabaseReference ordersRef = database.getReference("orders");
        String orderId = ordersRef.push().getKey();
        ordersRef.child(orderId).setValue(order)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(NewOrderActivity.this, "Η παραγγελία στάλθηκε επιτυχώς", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(NewOrderActivity.this, "Σφάλμα: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }
}