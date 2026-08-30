package com.example.maintenanceapp.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.Nullable;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class MaintenanceDocuments {

    private static final String TAG = "MaintDocs";

    private static final String DOCUMENT_URL = Api.MAINTENANCE_DOCUMENT;
    private static final String UPLOAD_URL = Api.MAINTENANCE_DOCUMENT;
    private static final int MAX_DECODE_DIMEN = 2560;

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MaintenanceDocuments() { }

    public interface Callbacks {
        void onLoaded(Bitmap bitmap);

        void onFailed();
    }

    @Nullable
    public static Bitmap cached(String documentId) {
        return documentId == null || documentId.isEmpty() ? null : CACHE.get(documentId);
    }

    /** Fetches and decodes one document */
    public static void load(Context ctx, String documentId, Callbacks cb) {
        if (documentId == null || documentId.trim().isEmpty()) {
            cb.onFailed();
            return;
        }
        final String id = documentId.trim();

        Bitmap hit = CACHE.get(id);
        if (hit != null) {
            cb.onLoaded(hit);
            return;
        }

        Request request = new Request.Builder()
                .url(DOCUMENT_URL + "?id=" + id)
                .get()
                .build();

        ApiClient.get(ctx).newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "GET document " + id + " failed", e);
                MAIN.post(cb::onFailed);
            }

            @Override
            public void onResponse(Call call, Response response) {
                byte[] bytes = null;
                try (Response r = response) {
                    ResponseBody body = r.body();
                    if (r.isSuccessful() && body != null) {
                        bytes = body.bytes();
                    } else {
                        Log.w(TAG, "GET document " + id + " -> HTTP " + r.code());
                    }
                } catch (IOException | OutOfMemoryError e) {
                    Log.w(TAG, "reading document " + id + " failed", e);
                }

                final Bitmap bitmap = bytes == null ? null : decode(bytes);
                if (bitmap == null) {
                    MAIN.post(cb::onFailed);
                    return;
                }
                CACHE.put(id, bitmap);
                MAIN.post(() -> cb.onLoaded(bitmap));
            }
        });
    }

    @Nullable
    private static Bitmap decode(byte[] bytes) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);

            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longest / (sample * 2) >= MAX_DECODE_DIMEN) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "not enough memory to decode document", e);
            return null;
        }
    }

    /** Uploads a document photo for a record */
    public static void upload(Context ctx, String recordId, byte[] jpeg, Callback callback) {
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("recordId", recordId)
                .addFormDataPart("file", "document.jpg",
                        RequestBody.create(jpeg, MediaType.parse("image/jpeg")))
                .build();

        Request request = new Request.Builder()
                .url(UPLOAD_URL)
                .post(body)
                .build();

        ApiClient.get(ctx).newCall(request).enqueue(callback);
    }
}
