/*
 * PickedImages.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

package com.example.maintenanceapp.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** supported formats: JPEG, PNG, WebP, GIF, HEIC */
public final class PickedImages {

    private static final String TAG = "PickedImages";

    private PickedImages() { }

    /** Decodes URI into a bitmap no larger than MAXDIMEN on its longest side */
    @Nullable
    public static Bitmap decodeUpright(Context ctx, Uri uri, int maxDimen) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimen);
        Bitmap bitmap;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bitmap == null) {
            return null;
        }

        // inSampleSize only halves, so this trims the remainder to the exact cap. Done before the
        // rotation so the matrix works on fewer pixels — scaleDown caps the *longest* side, which a
        // 90° turn doesn't change, so the order makes no difference to the result.
        bitmap = scaleDown(bitmap, maxDimen);
        return applyExifOrientation(ctx, uri, bitmap);
    }

    private static int sampleSize(int width, int height, int maxDimen) {
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / (sample * 2) >= maxDimen) {
            sample *= 2;
        }
        return sample;
    }

    /** Scales the bitmap so its longest side is at most maxDimen, preserving aspect ratio. */
    public static Bitmap scaleDown(Bitmap src, int maxDimen) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxDimen) {
            return src;
        }
        float ratio = (float) maxDimen / longest;
        return Bitmap.createScaledBitmap(src, Math.round(w * ratio), Math.round(h * ratio), true);
    }

    /* EXIF orientation is a metadata tag that cameras write into JPEG files to record how the sensor was physically rotated when the photo was taken */
    public static Bitmap applyExifOrientation(Context ctx, Uri uri, Bitmap bitmap) {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                orientation = new ExifInterface(in).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (IOException | OutOfMemoryError e) {
            Log.w(TAG, "could not read EXIF orientation", e);
            return bitmap;
        }

        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:      // mirror + 90°
                m.postRotate(90);
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:     // mirror + 270°
                m.postRotate(270);
                m.postScale(-1, 1);
                break;
            default:
                return bitmap;                             // NORMAL / UNDEFINED — nothing to do
        }

        try {
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "not enough memory to rotate photo", e);
            return bitmap;
        }
    }

    public static byte[] encodeLossless(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out);
        } else {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return out.toByteArray();
    }

    public static byte[] encodeJpeg(Bitmap bitmap, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }
}
