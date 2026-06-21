// BuiltinPrinter.java
package com.ads.paragelia;

import android.graphics.Bitmap;
import android.text.Layout;
import com.zcs.sdk.Printer;
import com.zcs.sdk.SdkResult;
import com.zcs.sdk.print.PrnStrFormat;
import com.zcs.sdk.print.PrnTextFont;
import com.zcs.sdk.print.PrnTextStyle;

public class BuiltinPrinter implements PrinterDevice {
    private Printer mPrinter;
    private String name;
    private String target;
    private boolean imageMode = false;

    public BuiltinPrinter(Printer printer) {
        this.mPrinter = printer;
    }

    public BuiltinPrinter(Printer printer, String name, String target) {
        this.mPrinter = printer;
        this.name = name;
        this.target = target;
    }

    public Printer getPrinter() {
        return mPrinter;
    }

    @Override
    public void setTarget(String target) {
        this.target = target;
    }

    @Override
    public void setImageMode(boolean enabled) {
        this.imageMode = enabled;
    }

    @Override
    public boolean isImageMode() {
        return imageMode;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getType() {
        return "BUILTIN";
    }

    @Override
    public String getTarget() {
        return target;
    }

    @Override
    public boolean isAvailable() {
        return mPrinter != null && mPrinter.getPrinterStatus() != SdkResult.SDK_PRN_STATUS_PAPEROUT;
    }

    @Override
    public void print(String text) {
        PrnStrFormat format = new PrnStrFormat();
        format.setTextSize(25);
        format.setStyle(PrnTextStyle.NORMAL);
        format.setFont(PrnTextFont.MONOSPACE);
        format.setAli(Layout.Alignment.ALIGN_NORMAL);
        mPrinter.setPrintAppendString(text, format);
        mPrinter.setPrintStart();
    }

    public void printBitmap(Bitmap bitmap) {
        if (mPrinter != null) {
            mPrinter.setPrintAppendBitmap(bitmap, Layout.Alignment.ALIGN_CENTER);
            mPrinter.setPrintStart();
        }
    }

    @Override
    public void cutPaper() {
        if (mPrinter.isSuppoerCutter()) mPrinter.openPrnCutter((byte) 1);
    }
}