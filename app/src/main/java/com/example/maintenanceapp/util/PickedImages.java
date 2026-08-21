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

/**
 * Turns a photo the user picked from the gallery into a bitmap that is the right way up and small
 * enough to upload.
 *
 * <p>Shared by the two pickers in the app — the vehicle photo on {@code AddVehicleActivity} and the
 * document photo on {@code AddMaintenanceActivity}. It exists as a util precisely because the EXIF
 * step below is the kind of thing that gets fixed in one screen and forgotten in the other.
 */
public final class PickedImages {

    private static final String TAG = "PickedImages";

    private PickedImages() { }

    /**
     * Decodes {@code uri} into a bitmap no larger than {@code maxDimen} on its longest side, rotated
     * to match its EXIF orientation.
     *
     * <p><b>Downsamples while decoding.</b> A 12 MP phone photo is ~48 MB as {@code ARGB_8888}, so
     * decoding it in full and only then scaling down — which is what this code used to do — risks an
     * {@link OutOfMemoryError} before the scale ever runs. The bounds-only pass costs one cheap read
     * and lets {@code inSampleSize} do most of the shrinking inside the decoder.
     *
     * @return the bitmap, or {@code null} if the image could not be decoded at all
     */
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

    /**
     * Largest power-of-two subsample that still leaves the longest side at or above {@code maxDimen},
     * so the follow-up exact scale never has to upscale. Returns 1 when the bounds are unknown
     * ({@code outWidth} is -1 for an undecodable stream) — the decode below will fail cleanly instead.
     */
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

    /**
     * Rotates/flips the bitmap to match the file's EXIF {@code Orientation} tag.
     *
     * <p><b>Why this is needed at all.</b> A phone camera doesn't rotate its sensor data — it stores a
     * portrait shot as landscape pixels plus an {@code Orientation} tag saying "turn me 90°", and every
     * gallery app honours it. {@link BitmapFactory} does not, so without this a portrait photo lands
     * sideways while landscape ones (tag = 1, no rotation) look fine. That asymmetry is the symptom.
     *
     * <p><b>And why it has to be baked into the pixels.</b> Both callers re-encode before upload, to
     * WebP/PNG or JPEG; the re-encode carries no EXIF, so the tag is gone by the time anything displays
     * it. There is no "keep the metadata" option — either the rotation is in the pixel data or it is
     * lost. This is also why the display-side helpers need no matching fix: everything downstream is
     * decoding an already-upright image.
     *
     * <p>Reads EXIF from its own {@code openInputStream}: the decode's stream is already consumed, and
     * a {@code ContentResolver} stream isn't reliably rewindable.
     *
     * <p>Uses AndroidX {@code ExifInterface} rather than the framework's, which can't read a stream
     * before API 24 and covers fewer container formats. Handles the mirrored orientations too — rare
     * (front cameras, some editors) but they arrive through the same tag.
     *
     * <p>Returns the input unchanged on any failure. A photo the right way up is worth having; a
     * missing or unreadable tag is not worth failing the pick over.
     */
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

    /**
     * Encodes losslessly — WebP on API 30+, PNG below. Used for the <b>vehicle</b> photo, where JPEG
     * would flatten a transparent PNG onto a solid background.
     */
    public static byte[] encodeLossless(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out);
        } else {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return out.toByteArray();
    }

    /**
     * Encodes as JPEG. Used for <b>document</b> photos, and the choice is deliberate: a photographed
     * receipt has no transparency to preserve and is mostly flat paper, so JPEG lands a legible 2048px
     * scan in a few hundred KB where lossless would be several MB. Legibility matters more than
     * pixel-exactness here, and the upload is the largest request the app makes.
     */
    public static byte[] encodeJpeg(Bitmap bitmap, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }
}
