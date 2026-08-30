package com.example.maintenanceapp.work;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.maintenanceapp.model.MaintenanceItem;
import com.example.maintenanceapp.model.Vehicle;
import com.example.maintenanceapp.model.VignetteInfo;
import com.example.maintenanceapp.util.Api;
import com.example.maintenanceapp.util.ApiClient;
import com.example.maintenanceapp.util.ComplianceStatus;
import com.example.maintenanceapp.util.MaintenanceStatus;
import com.example.maintenanceapp.util.VehicleType;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Periodic background check for vehicles whose service is due or overdue, posting a notification
 * when there are any. Scheduled by {@link ServiceReminders}.
 *
 * <p>This deliberately re-derives status from the same pieces the UI uses — {@code GET /vehicles},
 * {@code GET /vehicles/maintenance?id=}, {@link MaintenanceItem#listFromJson} and
 * {@link MaintenanceStatus#worst} — so a badge on the Автопарк tab and a notification can never
 * disagree about what's overdue. <b>No new endpoint is needed.</b>
 *
 * <p>It also checks <b>document validity</b> (винетка / ГТП / ГО) and notifies about that
 * separately — own channel, own notification id, own suppression signature. The two are kept apart
 * end to end because "your oil is due" and "your vignette expired" differ in both urgency and
 * remedy, and because a change in one must not reset the other's 7-day quiet period.
 *
 * <p>Requests are made <b>synchronously</b> ({@code execute()}, not {@code enqueue()}): a Worker
 * already runs off the main thread, and the callback style the Activities use would let
 * {@code doWork()} return before the responses landed.
 */
public class ServiceReminderWorker extends Worker {

    private static final String TAG = "ServiceReminders";


    /** Re-nudge about an unchanged backlog after this long, so ignoring it once isn't permanent. */
    private static final long REMIND_AGAIN_MS = TimeUnit.DAYS.toMillis(7);

    private static final String PREFS = "reminders";
    private static final String KEY_SIGNATURE = "last_signature";
    private static final String KEY_NOTIFIED_AT = "last_notified_at";

    /**
     * Documents get their <b>own</b> signature and timestamp, not a share of the service ones.
     * Folding both into one signature would mean a vignette changing state resets the suppression
     * clock for service reminders too — so the user would be re-notified about an oil change they
     * had already dismissed, because something unrelated happened to their vignette.
     */
    private static final String KEY_DOC_SIGNATURE = "last_doc_signature";
    private static final String KEY_DOC_NOTIFIED_AT = "last_doc_notified_at";

    public ServiceReminderWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();

        // Logged out (or never logged in): nothing to report, and every request would be
        // unauthenticated anyway. Not a failure — just skip this run.
        if (ApiClient.token(ctx).isEmpty()) {
            return Result.success();
        }

        OkHttpClient client = ApiClient.get(ctx);
        List<Vehicle> vehicles = fetchVehicles(client);
        if (vehicles == null) {
            // Couldn't reach the server; let WorkManager back off and try again rather than
            // treating "unknown" as "nothing due".
            return Result.retry();
        }

        List<String> overdue = new ArrayList<>();
        List<String> due = new ArrayList<>();
        List<String> signature = new ArrayList<>();

        List<String> docOverdue = new ArrayList<>();
        List<String> docDue = new ArrayList<>();
        List<String> docSignature = new ArrayList<>();
        // Remembered so a single-vehicle notification can deep-link straight to its Документи
        // screen. Only meaningful when exactly one vehicle ends up flagged.
        Vehicle onlyDocVehicle = null;

        for (Vehicle v : vehicles) {
            if (v.id == null || v.id.isEmpty()) {
                continue;
            }

            // ---- documents ----
            // The declared ГТП/ГО dates came with GET /vehicles, so they cost nothing. Only the
            // vignette needs a call, and a failed one leaves the declared verdict standing rather
            // than dropping the vehicle: "we couldn't check the vignette" must not erase "your ГТП
            // expired last week".
            // An exempt type (motorcycle) is not asked about at all — the authority's correct "no
            // vignette" answer would otherwise become a nightly notification about a document the
            // owner is not required to hold. See VehicleType.requiresVignette().
            VignetteInfo vignette = VehicleType.of(v).requiresVignette()
                    ? fetchVignette(client, v.id)
                    : null;
            ComplianceStatus docStatus = ComplianceStatus.worst(
                    ComplianceStatus.declared(v),
                    ComplianceStatus.ofVignette(vignette));
            if (docStatus == ComplianceStatus.OVERDUE || docStatus == ComplianceStatus.DUE) {
                if (docStatus == ComplianceStatus.OVERDUE) {
                    docOverdue.add(label(v));
                } else {
                    docDue.add(label(v));
                }
                docSignature.add(v.id + ":" + docStatus.name());
                onlyDocVehicle = v;
            }

            // ---- service ----
            List<MaintenanceItem> items = fetchMaintenance(client, v.id);
            if (items == null) {
                continue;   // this vehicle is unreachable; don't let it hide the others
            }
            MaintenanceStatus status = MaintenanceStatus.worst(items, v.mileage);
            if (status == MaintenanceStatus.OVERDUE) {
                overdue.add(label(v));
            } else if (status == MaintenanceStatus.DUE) {
                due.add(label(v));
            } else {
                continue;
            }
            signature.add(v.id + ":" + status.name());
        }

        notifyServiceIfChanged(ctx, overdue, due, signature);
        notifyDocumentsIfChanged(ctx, docOverdue, docDue, docSignature,
                docSignature.size() == 1 ? onlyDocVehicle : null);
        return Result.success();
    }

    /**
     * Sorted so the signature depends on which vehicles are in which state, not on the order the
     * server happened to list them in. {@code TextUtils.join}, not {@code String.join} — that one is
     * API 26 and minSdk here is 24.
     */
    private static String signatureOf(List<String> parts) {
        Collections.sort(parts);
        return TextUtils.join(",", parts);
    }

    private void notifyServiceIfChanged(Context ctx, List<String> overdue, List<String> due,
                                        List<String> signature) {
        if (overdue.isEmpty() && due.isEmpty()) {
            // Nothing outstanding — clear the record so the next problem notifies immediately
            // instead of being suppressed as "same as last time".
            prefs(ctx).edit().remove(KEY_SIGNATURE).remove(KEY_NOTIFIED_AT).apply();
            return;
        }
        String current = signatureOf(signature);
        if (shouldNotify(ctx, current, KEY_SIGNATURE, KEY_NOTIFIED_AT)) {
            ServiceReminders.notifyDue(ctx, overdue, due);
            prefs(ctx).edit()
                    .putString(KEY_SIGNATURE, current)
                    .putLong(KEY_NOTIFIED_AT, System.currentTimeMillis())
                    .apply();
        }
    }

    private void notifyDocumentsIfChanged(Context ctx, List<String> overdue, List<String> due,
                                          List<String> signature, Vehicle single) {
        if (overdue.isEmpty() && due.isEmpty()) {
            prefs(ctx).edit().remove(KEY_DOC_SIGNATURE).remove(KEY_DOC_NOTIFIED_AT).apply();
            return;
        }
        String current = signatureOf(signature);
        if (shouldNotify(ctx, current, KEY_DOC_SIGNATURE, KEY_DOC_NOTIFIED_AT)) {
            ServiceReminders.notifyDocumentsDue(ctx, overdue, due, single);
            prefs(ctx).edit()
                    .putString(KEY_DOC_SIGNATURE, current)
                    .putLong(KEY_DOC_NOTIFIED_AT, System.currentTimeMillis())
                    .apply();
        }
    }

    /**
     * Suppresses repeats: the same set of vehicles in the same states doesn't notify again until
     * {@link #REMIND_AGAIN_MS} has passed. Any change — a new overdue car, or one going from due
     * to overdue — notifies straight away.
     */
    private boolean shouldNotify(Context ctx, String signature, String sigKey, String atKey) {
        SharedPreferences prefs = prefs(ctx);
        if (!signature.equals(prefs.getString(sigKey, ""))) {
            return true;
        }
        return System.currentTimeMillis() - prefs.getLong(atKey, 0L) >= REMIND_AGAIN_MS;
    }

    /** @return the user's vehicles, or null if the server couldn't be reached / parsed. */
    private List<Vehicle> fetchVehicles(OkHttpClient client) {
        Request request = new Request.Builder().url(Api.VEHICLES).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                Log.e(TAG, "GET /vehicles -> HTTP " + response.code());
                return null;
            }
            JSONArray arr = new JSONObject(response.body().string()).optJSONArray("vehicles");
            List<Vehicle> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        list.add(Vehicle.fromJson(o));
                    }
                }
            }
            return list;
        } catch (IOException | JSONException e) {
            Log.e(TAG, "GET /vehicles failed", e);
            return null;
        }
    }

    /** @return one vehicle's maintenance items, or null if the request failed. */
    private List<MaintenanceItem> fetchMaintenance(OkHttpClient client, String id) {
        HttpUrl url = HttpUrl.parse(Api.MAINTENANCE).newBuilder()
                .addQueryParameter("id", id)
                .build();
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                Log.e(TAG, "GET /vehicles/maintenance?id=" + id + " -> HTTP " + response.code());
                return null;
            }
            return MaintenanceItem.listFromJson(response.body().string());
        } catch (IOException | JSONException e) {
            Log.e(TAG, "GET /vehicles/maintenance?id=" + id + " failed", e);
            return null;
        }
    }

    /**
     * One vehicle's vignette, or {@code null} when it could not be checked.
     *
     * <p>{@code null} is <em>unknown</em>, and {@link ComplianceStatus#ofVignette} maps it to no
     * status at all rather than to OVERDUE. A non-2xx here means our backend could not reach the
     * toll authority and had nothing cached — reporting that as "no vignette" would push a
     * notification claiming the user was driving illegally because a third-party service was down.
     */
    private VignetteInfo fetchVignette(OkHttpClient client, String id) {
        HttpUrl url = HttpUrl.parse(Api.VEHICLE_VIGNETTE).newBuilder()
                .addQueryParameter("id", id)
                .build();
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                Log.e(TAG, "GET /vehicles/vignette?id=" + id + " -> HTTP " + response.code());
                return null;
            }
            return VignetteInfo.fromJson(response.body().string());
        } catch (IOException | JSONException e) {
            Log.e(TAG, "GET /vehicles/vignette?id=" + id + " failed", e);
            return null;
        }
    }

    private static String label(Vehicle v) {
        String make = v.make == null ? "" : v.make.trim();
        String model = v.model == null ? "" : v.model.trim();
        String name = (make + " " + model).trim();
        return name.isEmpty() ? String.valueOf(v.licensePlate) : name;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
