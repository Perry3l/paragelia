package com.ads.paragelia;

import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.Nullable;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TakeAwayActivity extends BaseActivity {

    private DatabaseReference counterRef;
    private String currentOrderNumber;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_take_away);
        showMemoryOverlay();
        counterRef = FirebaseHelper.getReference("daily_counter");

        fetchNextOrderNumber(orderNumber -> {
            currentOrderNumber = orderNumber;
            ProductSelectionBottomSheet bottomSheet = ProductSelectionBottomSheet.newInstance("TA-" + orderNumber);
            bottomSheet.show(getSupportFragmentManager(), "takeaway_sheet");

            bottomSheet.setOnDismissListener(dialog -> finish());
        });
    }

    private void fetchNextOrderNumber(OrderNumberCallback callback) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        counterRef.child(today).runTransaction(new Transaction.Handler() {
            @Override
            public Transaction.Result doTransaction(MutableData currentData) {
                Long current = currentData.getValue(Long.class);
                if (current == null) {
                    currentData.setValue(1L);
                } else {
                    currentData.setValue(current + 1);
                }
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {
                if (committed) {
                    Long number = currentData.getValue(Long.class);
                    callback.onNumber(String.valueOf(number));
                } else {
                    Toast.makeText(TakeAwayActivity.this, "Σφάλμα δημιουργίας αριθμού", Toast.LENGTH_SHORT).show();
                    finish();
                }
            }
        });
    }

    interface OrderNumberCallback {
        void onNumber(String number);
    }
}