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

/**
 * Uploads and fetches the document photo attached to a maintenance record.
 *
 * <p><b>This is a third image transport, and it deliberately differs from {@link VehicleImages}.</b>
 * Vehicle photos travel as base64 inside JSON; documents travel as <b>raw bytes</b> with a real
 * {@code Content-Type} and {@code Content-Length}. Reasons, in order of weight:
 * <ul>
 *   <li>base64 is +33% over the wire, on mobile data, for the largest payloads the app moves;</li>
 *   <li>the client would pay double peak memory — the encoded string <em>and</em> the decoded
 *       bitmap;</li>
 *   <li>a raw body can be streamed and gives working {@code ETag}/{@code 304}, where a JSON string
 *       has to be buffered whole on both ends;</li>
 *   <li>if a response is ever cut short, a body measured against {@code Content-Length} fails
 *       detectably, where a truncated JSON body is simply unparseable and indistinguishable from
 *       corruption.</li>
 * </ul>
 *
 * <p><b>The list endpoints never carry document bytes</b> — {@code GET /vehicles/maintenance} and
 * {@code …/history} send only a {@code documentId} per record. A vehicle's history is tens of records,
 * so embedding images would multiply one photo by the whole history in a single response. Bytes are
 * fetched one at a time, here, when the user actually opens one.
 */
public final class MaintenanceDocuments {

    private static final String TAG = "MaintDocs";

    private static final String BASE = "http://92.5.55.85:27778";
    private static final String DOCUMENT_URL = BASE + "/vehicles/maintenance/document";
    private static final String UPLOAD_URL = BASE + "/vehicles/maintenance/document";

    /** Cap on the decoded document, in px on the longest side. Enough to read an invoice zoomed in. */
    private static final int MAX_DECODE_DIMEN = 2560;

    /**
     * Decoded documents keyed by document id. A document is immutable once uploaded (a new photo gets
     * a new id), so a cache hit can never be stale — which is what makes reopening the viewer instant
     * and stops a re-fetch on every rotation.
     */
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MaintenanceDocuments() { }

    /** Delivered on the main thread. Exactly one of the two methods is called. */
    public interface Callbacks {
        void onLoaded(Bitmap bitmap);

        void onFailed();
    }

    /** Cached bitmap for this document, or null. Lets a caller skip the spinner on a re-open. */
    @Nullable
    public static Bitmap cached(String documentId) {
        return documentId == null || documentId.isEmpty() ? null : CACHE.get(documentId);
    }

    /**
     * Fetches and decodes one document. Serves from the in-memory cache when possible.
     *
     * <p>Reads the whole body into a byte array before decoding rather than handing
     * {@code BitmapFactory} the stream directly: a decode straight off the socket can't be retried,
     * and a short read would surface as a half-drawn bitmap instead of a clean failure.
     */
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

    /**
     * Decodes with a bounds pass first, so a multi-megapixel scan is subsampled inside the decoder
     * rather than briefly existing at full size. Same reasoning as
     * {@link PickedImages#decodeUpright}.
     *
     * <p>No EXIF handling on this side on purpose: the upload path already baked the rotation into the
     * pixels, and the re-encode dropped the tag. There is nothing here to correct.
     */
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

    /**
     * Uploads a document photo for a record as {@code multipart/form-data}.
     *
     * <p><b>A separate request from creating the record, on purpose.</b> The service record is the data
     * that matters; the photo is a convenience. A multi-megabyte upload from a phone can fail
     * mid-request for reasons that have nothing to do with the server — a tunnel, a cell handover, the
     * app being backgrounded. Keeping them separate means such a failure loses only the photo: the
     * record is already saved, and the user is told the difference rather than being asked to retype a
     * service they already entered.
     */
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
