package com.ads.paragelia;

import androidx.annotation.NonNull;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.List;

public class SystemSettingsManager {
    private static SystemSettingsManager instance;
    private int maxTables = 10;
    private final List<Runnable> listeners = new ArrayList<>();
    private DatabaseReference settingsRef;

    private SystemSettingsManager() {
        settingsRef = FirebaseHelper.getReference("system_settings");
        settingsRef.child("max_tables").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Long value = snapshot.getValue(Long.class);
                if (value != null) {
                    maxTables = value.intValue();
                } else {
                    maxTables = 10;
                    settingsRef.child("max_tables").setValue(10);
                }
                for (Runnable r : listeners) r.run();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    public static synchronized SystemSettingsManager getInstance() {
        if (instance == null) instance = new SystemSettingsManager();
        return instance;
    }

    public int getMaxTables() { return maxTables; }
    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }

    public void setMaxTables(int newMax, Runnable onComplete) {
        settingsRef.child("max_tables").setValue(newMax)
                .addOnSuccessListener(aVoid -> { if (onComplete != null) onComplete.run(); })
                .addOnFailureListener(e -> { if (onComplete != null) onComplete.run(); });
    }
}