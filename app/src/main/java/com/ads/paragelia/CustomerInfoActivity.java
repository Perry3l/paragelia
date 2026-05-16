package com.ads.paragelia;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.database.DatabaseReference;
import java.util.HashMap;
import java.util.Map;

public class CustomerInfoActivity extends AppCompatActivity {
    private EditText etName, etAddress, etPhone, etNotes;
    private Button btnSave;
    private String orderId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_customer_info);

        orderId = getIntent().getStringExtra("order_id");

        etName = findViewById(R.id.etCustomerName);
        etAddress = findViewById(R.id.etCustomerAddress);
        etPhone = findViewById(R.id.etCustomerPhone);
        etNotes = findViewById(R.id.etCustomerNotes);
        btnSave = findViewById(R.id.btnSaveCustomer);

        btnSave.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String address = etAddress.getText().toString().trim();
            String phone = etPhone.getText().toString().trim();
            String notes = etNotes.getText().toString().trim();

            if (name.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "Όνομα και τηλέφωνο είναι υποχρεωτικά", Toast.LENGTH_SHORT).show();
                return;
            }

            DatabaseReference orderRef = FirebaseHelper.getReference("delivery_orders")
                    .child(orderId)
                    .child("current_order")
                    .child("customer");

            Map<String, Object> customerData = new HashMap<>();
            customerData.put("name", name);
            customerData.put("address", address);
            customerData.put("phone", phone);
            customerData.put("notes", notes);

            orderRef.setValue(customerData)
                    .addOnSuccessListener(aVoid -> {
                        Toast.makeText(this, "Η παραγγελία αποθηκεύτηκε!", Toast.LENGTH_SHORT).show();
                        finish();
                    })
                    .addOnFailureListener(e -> Toast.makeText(this, "Σφάλμα", Toast.LENGTH_SHORT).show());
        });
    }
}