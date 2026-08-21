package com.example.maintenanceapp.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.example.maintenanceapp.R;

import java.util.Locale;

/**
 * Puts a vehicle photo into an ImageView from whatever the server sent for it.
 *
 * <p>Two transports coexist, tried in this order by {@link #apply}:
 * <ol>
 *   <li><b>{@code imageBase64}</b> — the photo itself, stored in the database and sent inline.
 *       This is what {@code POST /vehicles/add} uploads.</li>
 *   <li><b>{@code imageName}</b> — a bare name (lowercase, no extension) matching a drawable
 *       bundled in the APK, resolved via {@code Resources.getIdentifier}. Used by seed data whose
 *       photos ship with the app, and as the fallback when no base64 came back.</li>
 * </ol>
 * Neither → the {@code *_placeholder} drawable for the vehicle's own {@link VehicleType}, so a bike
 * with no photo doesn't sit in the list wearing a car.
 *
 * <p>Note that {@code res/drawable} is compiled into the APK at build time and is read-only at
 * runtime, so the name transport can only ever refer to photos that shipped with the app — a photo
 * the user picks has to travel as base64.
 */
public final class VehicleImages {

    /** Decoded photos, keyed by the caller's cache key. Base64-decoding plus a bitmap decode on
     *  every RecyclerView bind would jank the list. Sized as a fraction of the app's heap. */
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private VehicleImages() { }

    /**
     * Shows the photo described by {@code base64} / {@code name} in {@code target}, preferring the
     * base64 payload and falling back to the bundled drawable and then the placeholder. Every
     * argument may be null or empty.
     *
     * @param cacheKey identity to cache the decoded bitmap under — pass the vehicle id for list
     *                 rows so scrolling doesn't re-decode, or null to skip the cache.
     * @param type     decides which placeholder is used when there is no photo at all; null reads
     *                 as {@link VehicleType#DEFAULT}.
     */
    public static void apply(Context ctx, ImageView target, String base64, String name,
                             String cacheKey, VehicleType type) {
        Bitmap bitmap = decode(base64, cacheKey);
        if (bitmap != null) {
            target.setImageBitmap(bitmap);
        } else {
            target.setImageResource(resolve(ctx, name, type));
        }
    }

    /** Drops {@code cacheKey}'s decoded bitmap, so the next {@link #apply} re-decodes it. */
    public static void evict(String cacheKey) {
        if (cacheKey != null) {
            CACHE.remove(cacheKey);
        }
    }

    /**
     * @return the drawable resource id for {@code name}, or {@code type}'s placeholder when the name
     *         is empty or no matching drawable exists. Matching is case-insensitive and any file
     *         extension in the name is ignored.
     */
    public static int resolve(Context ctx, String name, VehicleType type) {
        if (name != null && !name.trim().isEmpty()) {
            String key = name.trim().toLowerCase(Locale.US);
            int dot = key.lastIndexOf('.');
            if (dot > 0) {
                key = key.substring(0, dot);   // tolerate "bmw_320d.png" -> "bmw_320d"
            }
            int resId = ctx.getResources().getIdentifier(key, "drawable", ctx.getPackageName());
            if (resId != 0) {
                return resId;
            }
        }
        return (type == null ? VehicleType.DEFAULT : type).placeholderRes;
    }

    /** @return the bitmap for a base64 payload, or null if there is none or it won't decode. */
    private static Bitmap decode(String base64, String cacheKey) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        if (cacheKey != null) {
            Bitmap cached = CACHE.get(cacheKey);
            if (cached != null && !cached.isRecycled()) {
                return cached;
            }
        }
        Bitmap bitmap = null;
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) {
                // The base64 was well-formed but the bytes aren't a whole image — the signature of
                // a payload cut mid-PNG. Logged loudly because it otherwise looks like "no photo".
                Log.e("VehicleImages", "decoded " + bytes.length
                        + " bytes but they are not a complete image (truncated?)");
            } else {
                Log.i("VehicleImages", "decoded " + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " from " + bytes.length + " bytes");
            }
        } catch (IllegalArgumentException e) {
            // Malformed base64 — most likely a response the server truncated mid-payload.
            Log.e("VehicleImages", "could not decode image payload", e);
        } catch (OutOfMemoryError e) {
            Log.e("VehicleImages", "image too large to decode", e);
        }
        if (bitmap != null && cacheKey != null) {
            CACHE.put(cacheKey, bitmap);
        }
        return bitmap;
    }
}
