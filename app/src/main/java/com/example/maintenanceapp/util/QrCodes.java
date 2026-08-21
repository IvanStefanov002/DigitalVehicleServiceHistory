package com.example.maintenanceapp.util;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * Renders a string as a QR bitmap. Uses ZXing's pure-Java {@code core} only — the app encodes
 * codes, it never scans them, so the camera-bearing android-embedded artifact isn't needed.
 */
public final class QrCodes {

    private QrCodes() { }

    /**
     * @param size the bitmap's edge in pixels; the matrix is scaled to fit.
     * @return the encoded bitmap, or null if the content can't be encoded.
     */
    public static Bitmap encode(String content, int size) {
        if (content == null || content.isEmpty() || size <= 0) {
            return null;
        }
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        // Level M survives a scuffed screen or an awkward angle without inflating the module count
        // the way H would; these links are short, so there's headroom either way.
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);   // quiet zone in modules; the layout adds real padding

        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    // Always black-on-white, never theme colours: scanners expect a dark code on a
                    // light quiet zone, and an inverted one in dark mode reads slowly or not at all.
                    pixels[row + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (WriterException | IllegalArgumentException e) {
            return null;
        }
    }
}
