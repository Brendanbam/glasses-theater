package com.brendanbam.glassestheater;

import android.graphics.Bitmap;
import android.graphics.Color;

/** Page clean-up for the reader. */
final class PageTools {

    private PageTools() { }

    /**
     * Crops plain white or black borders around a page (like Mihon's "Crop borders"), so the art
     * fills more of the glasses view. Pages without a clear border are left untouched.
     */
    static Bitmap cropBorders(Bitmap b) {
        try {
            int w = b.getWidth(), h = b.getHeight();
            if (w < 64 || h < 64) return b;
            int ref = luma(b.getPixel(2, 2));
            if (ref > 30 && ref < 225) return b; // corner isn't white or black: no border to crop
            int[] line = new int[Math.max(w, h)];

            int top = 0;
            while (top < h * 0.4f && rowPlain(b, top, ref, line)) top++;
            int bottom = h - 1;
            while (bottom > top + h * 0.2f && rowPlain(b, bottom, ref, line)) bottom--;
            int left = 0;
            while (left < w * 0.4f && colPlain(b, left, top, bottom, ref, line)) left++;
            int right = w - 1;
            while (right > left + w * 0.2f && colPlain(b, right, top, bottom, ref, line)) right--;

            // Keep a hair of margin, and skip trivial crops
            int m = Math.max(2, Math.min(w, h) / 200);
            left = Math.max(0, left - m); top = Math.max(0, top - m);
            right = Math.min(w - 1, right + m); bottom = Math.min(h - 1, bottom + m);
            int cw = right - left + 1, ch = bottom - top + 1;
            if (cw > w * 0.98f && ch > h * 0.98f) return b;
            if (cw < w * 0.5f || ch < h * 0.5f) return b; // looks like content, not a border
            return Bitmap.createBitmap(b, left, top, cw, ch);
        } catch (Exception | OutOfMemoryError e) {
            return b;
        }
    }

    private static boolean rowPlain(Bitmap b, int y, int ref, int[] line) {
        int w = b.getWidth();
        b.getPixels(line, 0, w, 0, y, w, 1);
        return plain(line, w, ref);
    }

    private static boolean colPlain(Bitmap b, int x, int top, int bottom, int ref, int[] line) {
        int n = bottom - top + 1;
        if (n <= 0) return false;
        b.getPixels(line, 0, 1, x, top, 1, n);
        return plain(line, n, ref);
    }

    /** True when (almost) every pixel matches the border color; a few specks of scan noise are allowed. */
    private static boolean plain(int[] px, int n, int ref) {
        int off = 0, allowed = Math.max(2, n / 250);
        for (int i = 0; i < n; i += 2) {
            if (Math.abs(luma(px[i]) - ref) > 28 && ++off > allowed) return false;
        }
        return true;
    }

    private static int luma(int c) {
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
    }
}
