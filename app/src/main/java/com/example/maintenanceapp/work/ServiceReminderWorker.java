/*
 * ServiceReminderWorker.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

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

/** Periodic background check for vehicles whose service is due or overdue, posting a notification when there are any. */
public class ServiceReminderWorker extends Worker {

    private static final String TAG = "ServiceReminders";
    private static final long REMIND_AGAIN_MS = TimeUnit.DAYS.toMillis(7);

    private static final String PREFS = "reminders";
    private static final String KEY_SIGNATURE = "last_signature";
    private static final String KEY_NOTIFIED_AT = "last_notified_at";
    private static final String KEY_DOC_SIGNATURE = "last_doc_signature";
    private static final String KEY_DOC_NOTIFIED_AT = "last_doc_notified_at";

    public ServiceReminderWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();

        if (ApiClient.token(ctx).isEmpty()) {
            return Result.success();
        }

        OkHttpClient client = ApiClient.get(ctx);
        List<Vehicle> vehicles = fetchVehicles(client);
        if (vehicles == null) {
            // Couldn't reach the server;
            return Result.retry();
        }

        List<String> overdue = new ArrayList<>();
        List<String> due = new ArrayList<>();
        List<String> signature = new ArrayList<>();

        List<String> docOverdue = new ArrayList<>();
        List<String> docDue = new ArrayList<>();
        List<String> docSignature = new ArrayList<>();

        Vehicle onlyDocVehicle = null;

        for (Vehicle v : vehicles) {
            if (v.id == null || v.id.isEmpty()) {
                continue;
            }

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
                continue;   // this vehicle is unreachable;
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

    private boolean shouldNotify(Context ctx, String signature, String sigKey, String atKey) {
        SharedPreferences prefs = prefs(ctx);
        if (!signature.equals(prefs.getString(sigKey, ""))) {
            return true;
        }
        return System.currentTimeMillis() - prefs.getLong(atKey, 0L) >= REMIND_AGAIN_MS;
    }

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
