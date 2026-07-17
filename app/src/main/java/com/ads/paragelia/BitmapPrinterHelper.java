package com.ads.paragelia;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.io.ByteArrayOutputStream;

public class BitmapPrinterHelper {

    public static Bitmap textToBitmap(String text, int width, int fontSize) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(fontSize);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.FILL);

        String[] lines = text.split("\n");
        int lineHeight = (int) (fontSize * 1.2f);
        int height = lines.length * lineHeight + 20;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        int y = lineHeight;
        for (String line : lines) {
            canvas.drawText(line, 10, y, paint);
            y += lineHeight;
        }
        return bitmap;
    }

    /**
     * Απόκομμα Take Away: τίτλος, ΤΕΡΑΣΤΙΟ νούμερο στο κέντρο, και υπότιτλος (ώρα).
     * Βγαίνει ως εικόνα ώστε το νούμερο να τυπώνεται μεγάλο σε κάθε τύπο εκτυπωτή.
     */
    public static Bitmap takeawayNumberSlip(String title, String number, String subtitle, int width) {
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        titlePaint.setTextSize(40);
        titlePaint.setColor(Color.BLACK);
        titlePaint.setTextAlign(Paint.Align.CENTER);

        Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        numberPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        numberPaint.setColor(Color.BLACK);
        numberPaint.setTextAlign(Paint.Align.CENTER);
        float numberSize = 220f;
        numberPaint.setTextSize(numberSize);
        // Σμίκρυνση αν το νούμερο δεν χωράει στο πλάτος του χαρτιού
        while (numberPaint.measureText(number) > width - 40 && numberSize > 60f) {
            numberSize -= 10f;
            numberPaint.setTextSize(numberSize);
        }

        Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        subPaint.setTypeface(Typeface.MONOSPACE);
        subPaint.setTextSize(30);
        subPaint.setColor(Color.BLACK);
        subPaint.setTextAlign(Paint.Align.CENTER);

        int titleY = 60;
        int numberY = titleY + (int) numberSize + 30;
        int subY = numberY + 60;
        int height = subY + 40;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        float cx = width / 2f;
        canvas.drawText(title, cx, titleY, titlePaint);
        canvas.drawText(number, cx, numberY, numberPaint);
        canvas.drawText(subtitle, cx, subY, subPaint);
        return bitmap;
    }

    public static byte[] bitmapToEscPos(Bitmap bitmap) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int bytesPerLine = (width + 7) / 8;
        byte[] raster = new byte[bytesPerLine * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.red(pixel) < 128) {
                    int byteIndex = y * bytesPerLine + (x / 8);
                    int bit = 7 - (x % 8);
                    raster[byteIndex] |= (1 << bit);
                }
            }
        }

        baos.write(29);
        baos.write(118);
        baos.write(48);
        baos.write(0);
        baos.write((byte)(bytesPerLine % 256));
        baos.write((byte)(bytesPerLine / 256));
        baos.write((byte)(height % 256));
        baos.write((byte)(height / 256));

        baos.write(raster, 0, raster.length);
        return baos.toByteArray();
    }
}