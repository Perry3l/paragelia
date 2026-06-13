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

    public static byte[] bitmapToEscPos(Bitmap bitmap) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int bytesPerLine = (width + 7) / 8;
        byte[] raster = new byte[bytesPerLine * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.red(pixel) < 128) { // μαύρο
                    int byteIndex = y * bytesPerLine + (x / 8);
                    int bit = 7 - (x % 8);
                    raster[byteIndex] |= (1 << bit);
                }
            }
        }

        // GS v 0 (ESC/POS raster command)
        baos.write(29);  // GS
        baos.write(118); // v
        baos.write(48);  // 0
        baos.write(0);   // m = 0
        baos.write((byte)(bytesPerLine % 256));
        baos.write((byte)(bytesPerLine / 256));
        baos.write((byte)(height % 256));
        baos.write((byte)(height / 256));

        baos.write(raster, 0, raster.length);
        return baos.toByteArray();
    }
}