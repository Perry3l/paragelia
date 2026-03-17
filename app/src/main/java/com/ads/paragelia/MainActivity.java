package com.ads.paragelia;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Βρίσκουμε τα CardView
        CardView cardNewOrder = findViewById(R.id.cardNewOrder);
        CardView cardTables = findViewById(R.id.cardTables);
        CardView cardHistory = findViewById(R.id.cardHistory);
        CardView cardReports = findViewById(R.id.cardReports);

        // Ορίζουμε listeners
        cardNewOrder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Ξεκινάμε activity για νέα παραγγελία
                // Intent intent = new Intent(MainActivity.this, NewOrderActivity.class);
                // startActivity(intent);
                Toast.makeText(MainActivity.this, "Νέα Παραγγελία - θα υλοποιηθεί", Toast.LENGTH_SHORT).show();
            }
        });

        cardTables.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Διαχείριση Τραπεζιών", Toast.LENGTH_SHORT).show();
            }
        });

        cardHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Ιστορικό Παραγγελιών", Toast.LENGTH_SHORT).show();
            }
        });

        cardReports.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Αναφορές", Toast.LENGTH_SHORT).show();
            }
        });
    }
}