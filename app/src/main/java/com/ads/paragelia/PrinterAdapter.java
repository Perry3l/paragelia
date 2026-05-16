package com.ads.paragelia;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Arrays;
import java.util.List;

public class PrinterAdapter extends RecyclerView.Adapter<PrinterAdapter.ViewHolder> {

    private List<PrinterDevice> printers;
    private OnPrinterActionListener listener;

    // Διαθέσιμα targets
    public static final List<String> TARGETS = Arrays.asList("RECEIPT", "KITCHEN", "BAR");

    public interface OnPrinterActionListener {
        void onTargetChanged(PrinterDevice printer, String newTarget);
        void onRemovePrinter(PrinterDevice printer);
    }

    public PrinterAdapter(List<PrinterDevice> printers, OnPrinterActionListener listener) {
        this.printers = printers;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_printer, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PrinterDevice printer = printers.get(position);

        holder.tvName.setText(printer.getName());
        String info = "Τύπος: " + printer.getType();
        if (printer instanceof NetworkPrinter) {
            info += " | IP: " + ((NetworkPrinter)printer).getIp();
        } else if (printer instanceof UsbPrinter) {
            info += " | VID: " + ((UsbPrinter)printer).getVendorId();
        }
        info += " | " + (printer.isAvailable() ? "✅ Online" : "❌ Offline");
        holder.tvInfo.setText(info);

        // Spinner για target
        ArrayAdapter<String> adapter = new ArrayAdapter<>(holder.spTarget.getContext(),
                android.R.layout.simple_spinner_item, TARGETS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        holder.spTarget.setAdapter(adapter);

        // Ορισμός τρέχοντος target χωρίς να πυροδοτηθεί ο listener
        holder.spTarget.setOnItemSelectedListener(null);
        int index = TARGETS.indexOf(printer.getTarget());
        if (index >= 0) holder.spTarget.setSelection(index);

        holder.spTarget.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                String selected = TARGETS.get(pos);
                if (!selected.equals(printer.getTarget())) {
                    listener.onTargetChanged(printer, selected);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Κουμπί αφαίρεσης (εμφανίζεται μόνο για USB/IP)
        boolean removable = printer instanceof UsbPrinter || printer instanceof NetworkPrinter;
        holder.btnRemove.setVisibility(removable ? View.VISIBLE : View.GONE);
        holder.btnRemove.setOnClickListener(v -> listener.onRemovePrinter(printer));
    }

    @Override
    public int getItemCount() {
        return printers.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvInfo;
        Spinner spTarget;
        Button btnRemove;

        ViewHolder(View v) {
            super(v);
            tvName = v.findViewById(R.id.tvPrinterName);
            tvInfo = v.findViewById(R.id.tvPrinterInfo);
            spTarget = v.findViewById(R.id.spTarget);
            btnRemove = v.findViewById(R.id.btnRemovePrinter);
        }
    }
}