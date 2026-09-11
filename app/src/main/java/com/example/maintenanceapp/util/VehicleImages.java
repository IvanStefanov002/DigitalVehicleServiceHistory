/*
 * VehicleImages.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

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

/** Puts a vehicle photo into an ImageView from whatever the server sent for it. */
public final class VehicleImages {

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private VehicleImages() { }

    public static void apply(Context ctx, ImageView target, String base64, String name,
                             String cacheKey, VehicleType type) {
        Bitmap bitmap = decode(base64, cacheKey);
        if (bitmap == null && (base64 == null || base64.isEmpty())) {
            bitmap = cached(cacheKey);
        }
        if (bitmap != null) {
            target.setImageBitmap(bitmap);
        } else {
            target.setImageResource(resolve(ctx, name, type));
        }
    }

    public static Bitmap cached(String cacheKey) {
        if (cacheKey == null) {
            return null;
        }
        Bitmap bitmap = CACHE.get(cacheKey);
        return bitmap != null && !bitmap.isRecycled() ? bitmap : null;
    }

    public static void evict(String cacheKey) {
        if (cacheKey != null) {
            CACHE.remove(cacheKey);
        }
    }

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

    /** Return the bitmap for a base64 payload, or null if there is none or it won't decode. */
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
                Log.e("VehicleImages", "decoded " + bytes.length
                        + " bytes but they are not a complete image (truncated?)");
            } else {
                Log.i("VehicleImages", "decoded " + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " from " + bytes.length + " bytes");
            }
        } catch (IllegalArgumentException e) {
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
