package com.ads.paragelia;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class PrinterManagementActivity extends BaseActivity {

    private RecyclerView rvPrinters;
    private PrinterManager printerManager;
    private UsbPrinterManager usbPrinterManager;
    private PrinterAdapterForManagement adapter;
    private List<PrinterDevice> printerList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_printer_management);
        showMemoryOverlay();

        rvPrinters = findViewById(R.id.rvPrinters);
        rvPrinters.setLayoutManager(new LinearLayoutManager(this));

        printerManager = PrinterManager.getInstance(this);
        printerManager.loadPrintersConfig();
        usbPrinterManager = new UsbPrinterManager(this);

        refreshList();

        Button btnAddNetwork = findViewById(R.id.btnAddNetworkPrinter);
        Button btnScanUsb = findViewById(R.id.btnScanUsb);
        Button btnSaveClose = findViewById(R.id.btnSaveAndClose);

        btnAddNetwork.setOnClickListener(v -> showAddNetworkPrinterDialog());
        btnScanUsb.setOnClickListener(v -> scanUsbPrinters());
        btnSaveClose.setOnClickListener(v -> {
            printerManager.savePrintersConfig();
            finish();
        });
    }

    private void refreshList() {
        printerList.clear();
        printerList.addAll(printerManager.getPrinters());
        if (adapter == null) {
            adapter = new PrinterAdapterForManagement(printerList);
            rvPrinters.setAdapter(adapter);
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private void showAddNetworkPrinterDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_add_network_printer, null);
        EditText etName = view.findViewById(R.id.etPrinterName);
        EditText etIp = view.findViewById(R.id.etIp);
        EditText etPort = view.findViewById(R.id.etPort);
        builder.setView(view)
                .setTitle("Προσθήκη Δικτυακού Εκτυπωτή")
                .setPositiveButton("Προσθήκη", (dialog, which) -> {
                    String name = etName.getText().toString().trim();
                    String ip = etIp.getText().toString().trim();
                    String portStr = etPort.getText().toString().trim();
                    if (name.isEmpty() || ip.isEmpty() || portStr.isEmpty()) {
                        Toast.makeText(this, "Συμπληρώστε όλα τα πεδία", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int port = Integer.parseInt(portStr);
                    NetworkPrinter printer = new NetworkPrinter(name, "RECEIPT", ip, port);
                    printerManager.addPrinter(printer);
                    refreshList();
                    Toast.makeText(this, "Ο δικτυακός εκτυπωτής προστέθηκε", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Ακύρωση", null)
                .show();
    }

    private void scanUsbPrinters() {
        List<UsbDevice> devices = usbPrinterManager.findPrinters();
        if (devices.isEmpty()) {
            Toast.makeText(this, "Δεν βρέθηκαν USB εκτυπωτές", Toast.LENGTH_SHORT).show();
            return;
        }
        for (UsbDevice dev : devices) {
            if (!usbPrinterManager.hasPermission(dev)) {
                usbPrinterManager.requestPermission(dev);
                Toast.makeText(this, "Χορηγήστε άδεια για τον USB εκτυπωτή και ξαναπατήστε Σάρωση", Toast.LENGTH_LONG).show();
                return;
            }
        }
        // Όλες έχουν άδεια – προσθήκη
        for (UsbDevice dev : devices) {
            boolean alreadyExists = false;
            for (PrinterDevice p : printerManager.getPrinters()) {
                if (p instanceof UsbPrinter) {
                    UsbPrinter up = (UsbPrinter) p;
                    if (up.getVendorId() == dev.getVendorId() && up.getProductId() == dev.getProductId()) {
                        alreadyExists = true;
                        break;
                    }
                }
            }
            if (!alreadyExists) {
                String name = dev.getProductName() != null ? dev.getProductName() : "USB " + dev.getDeviceName();
                UsbPrinter usbPrinter = new UsbPrinter(usbPrinterManager, dev, name, "RECEIPT");
                printerManager.addPrinter(usbPrinter);
            }
        }
        refreshList();
        Toast.makeText(this, "Η σάρωση ολοκληρώθηκε", Toast.LENGTH_SHORT).show();
    }

    // ---------- Adapter για τη λίστα εκτυπωτών με δυνατότητα επεξεργασίας ----------
    private class PrinterAdapterForManagement extends RecyclerView.Adapter<PrinterAdapterForManagement.ViewHolder> {
        private List<PrinterDevice> printers;

        PrinterAdapterForManagement(List<PrinterDevice> printers) {
            this.printers = printers;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_printer_management, parent, false);
            return new ViewHolder(view);
        }



        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            PrinterDevice printer = printers.get(position);
            holder.tvName.setText(printer.getName());
            holder.tvInfo.setText(String.format("Τύπος: %s | %s", printer.getType(),
                    printer.isAvailable() ? "✅ Online" : "❌ Offline"));

            SwitchCompat switchImageMode = holder.itemView.findViewById(R.id.switchImageMode);
            switchImageMode.setChecked(printer.isImageMode());
            switchImageMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                printer.setImageMode(isChecked);
                // προαιρετικά: αποθήκευση αμέσως
                printerManager.savePrintersConfig();
            });

            // Spinner target
            ArrayAdapter<String> targetAdapter = new ArrayAdapter<>(holder.itemView.getContext(),
                    android.R.layout.simple_spinner_item, PrinterAdapter.TARGETS);
            targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            holder.spTarget.setAdapter(targetAdapter);
            int targetIndex = PrinterAdapter.TARGETS.indexOf(printer.getTarget());
            if (targetIndex >= 0) holder.spTarget.setSelection(targetIndex);
            holder.spTarget.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    String newTarget = PrinterAdapter.TARGETS.get(pos);
                    if (!newTarget.equals(printer.getTarget())) {
                        printer.setTarget(newTarget);
                        Toast.makeText(PrinterManagementActivity.this,
                                "Target άλλαξε σε " + newTarget + " για " + printer.getName(), Toast.LENGTH_SHORT).show();
                    }
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });

            // Κουμπί επεξεργασίας ονόματος
            holder.btnEditName.setOnClickListener(v -> {
                EditText input = new EditText(PrinterManagementActivity.this);
                input.setText(printer.getName());
                new AlertDialog.Builder(PrinterManagementActivity.this)
                        .setTitle("Αλλαγή ονόματος")
                        .setView(input)
                        .setPositiveButton("Αποθήκευση", (dialog, which) -> {
                            String newName = input.getText().toString().trim();
                            if (!newName.isEmpty()) {
                                // Δεν μπορούμε να αλλάξουμε το όνομα εύκολα – θα αφαιρέσουμε και θα προσθέσουμε νέο αντικείμενο?
                                // Απλά αποθηκεύουμε προσωρινά, αλλά το PrinterDevice δεν έχει setter για όνομα.
                                // Για απλότητα, θα δημιουργήσουμε νέο αντικείμενο ίδιου τύπου.
                                replacePrinter(printer, newName);
                            }
                        })
                        .setNegativeButton("Ακύρωση", null)
                        .show();
            });

            // Κουμπί δοκιμής εκτύπωσης
            holder.btnTestPrint.setOnClickListener(v -> {
                if (!printer.isAvailable()) {
                    Toast.makeText(PrinterManagementActivity.this, "Ο εκτυπωτής δεν είναι διαθέσιμος", Toast.LENGTH_SHORT).show();
                    return;
                }
                new Thread(() -> {
                    printer.print("ΔΟΚΙΜΑΣΤΙΚΗ ΕΚΤΥΠΩΣΗ\nΑπό διαχείριση εκτυπωτών\n" +
                            "Ημερομηνία: " + android.text.format.DateFormat.format("dd/MM/yyyy HH:mm:ss", System.currentTimeMillis()) + "\n\n\n");
                    printer.cutPaper();
                }).start();
                Toast.makeText(PrinterManagementActivity.this, "Εκτύπωση δοκιμής σε " + printer.getName(), Toast.LENGTH_SHORT).show();
            });

            // Κουμπί διαγραφής (μόνο για USB και IP)
            boolean removable = printer instanceof UsbPrinter || printer instanceof NetworkPrinter;
            holder.btnRemove.setVisibility(removable ? View.VISIBLE : View.GONE);
            holder.btnRemove.setOnClickListener(v -> {
                printerManager.removePrinter(printer);
                refreshList();
                Toast.makeText(PrinterManagementActivity.this, "Ο εκτυπωτής αφαιρέθηκε", Toast.LENGTH_SHORT).show();
            });
        }

        private void replacePrinter(PrinterDevice oldPrinter, String newName) {
            PrinterDevice newPrinter = null;
            if (oldPrinter instanceof UsbPrinter) {
                UsbPrinter old = (UsbPrinter) oldPrinter;
                newPrinter = new UsbPrinter(usbPrinterManager, old.getUsbDevice(), newName, old.getTarget());
            } else if (oldPrinter instanceof NetworkPrinter) {
                NetworkPrinter old = (NetworkPrinter) oldPrinter;
                newPrinter = new NetworkPrinter(newName, old.getTarget(), old.getIp(), old.getPort());
            } else if (oldPrinter instanceof BuiltinPrinter) {
                // Δεν επιτρέπεται αλλαγή ονόματος ενσωματωμένου
                Toast.makeText(PrinterManagementActivity.this, "Δεν μπορείτε να αλλάξετε όνομα ενσωματωμένου εκτυπωτή", Toast.LENGTH_SHORT).show();
                return;
            }
            if (newPrinter != null) {
                int index = printerManager.getPrinters().indexOf(oldPrinter);
                printerManager.removePrinter(oldPrinter);
                printerManager.addPrinter(newPrinter);
                refreshList();
                Toast.makeText(PrinterManagementActivity.this, "Το όνομα άλλαξε σε " + newName, Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public int getItemCount() {
            return printers.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvInfo;
            Spinner spTarget;
            Button btnEditName, btnTestPrint, btnRemove;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvPrinterName);
                tvInfo = itemView.findViewById(R.id.tvPrinterInfo);
                spTarget = itemView.findViewById(R.id.spTarget);
                btnEditName = itemView.findViewById(R.id.btnEditName);
                btnTestPrint = itemView.findViewById(R.id.btnTestPrint);
                btnRemove = itemView.findViewById(R.id.btnRemovePrinter);
            }
        }
    }
}