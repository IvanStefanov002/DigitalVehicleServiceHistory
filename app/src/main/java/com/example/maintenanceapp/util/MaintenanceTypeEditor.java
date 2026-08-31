/*
 * MaintenanceTypeEditor.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

package com.example.maintenanceapp.util;

import android.app.Activity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.example.maintenanceapp.R;
import com.example.maintenanceapp.model.MaintenanceType;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class MaintenanceTypeEditor {

    public interface Listener {
        void onCatalogChanged();
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_INTERVAL_KM = 500000;
    private static final int MAX_INTERVAL_MONTHS = 240;

    private MaintenanceTypeEditor() { }

    public static void createType(Activity activity, Listener listener) {
        show(activity, null, listener);
    }

    public static void editType(Activity activity, MaintenanceType type, Listener listener) {
        show(activity, type, listener);
    }

    private static void show(Activity activity, @Nullable MaintenanceType type, Listener listener) {
        View form = LayoutInflater.from(activity).inflate(R.layout.dialog_maintenance_type, null, false);

        TextInputLayout tilName = form.findViewById(R.id.tilTypeName);
        EditText edtName = form.findViewById(R.id.edtTypeName);
        TextInputLayout tilKm = form.findViewById(R.id.tilTypeKm);
        EditText edtKm = form.findViewById(R.id.edtTypeKm);
        TextInputLayout tilMonths = form.findViewById(R.id.tilTypeMonths);
        EditText edtMonths = form.findViewById(R.id.edtTypeMonths);
        TextView txtSuggested = form.findViewById(R.id.txtTypeSuggested);

        boolean creating = type == null;
        boolean renameable = creating || type.custom;

        if (!creating) {
            edtName.setText(type.name);
            if (type.tracksKm()) {
                edtKm.setText(String.valueOf(type.defaultIntervalKm));
            }
            if (type.tracksTime()) {
                edtMonths.setText(String.valueOf(type.defaultIntervalMonths));
            }
        }
        if (!renameable) {
            edtName.setEnabled(false);
            tilName.setHelperTextEnabled(true);
            tilName.setHelperText(activity.getString(R.string.mt_name_builtin));
        }

        String suggestion = creating ? "" : suggestionText(activity, type);
        txtSuggested.setVisibility(suggestion.isEmpty() ? View.GONE : View.VISIBLE);
        txtSuggested.setText(suggestion);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(creating ? R.string.mt_create_title : R.string.mt_edit_title)
                .setView(form)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.am_save, null);

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    tilName.setError(null);
                    tilKm.setError(null);
                    tilMonths.setError(null);

                    String name = edtName.getText().toString().trim();
                    if (name.isEmpty()) {
                        tilName.setError(activity.getString(R.string.mt_name_required));
                        return;
                    }

                    Integer km = parseInterval(edtKm.getText().toString(), MAX_INTERVAL_KM);
                    if (km == null && !edtKm.getText().toString().trim().isEmpty()) {
                        tilKm.setError(activity.getString(R.string.mt_interval_invalid));
                        return;
                    }
                    Integer months = parseInterval(edtMonths.getText().toString(), MAX_INTERVAL_MONTHS);
                    if (months == null && !edtMonths.getText().toString().trim().isEmpty()) {
                        tilMonths.setError(activity.getString(R.string.mt_interval_invalid));
                        return;
                    }
                    if (km == null && months == null) {
                        tilKm.setError(activity.getString(R.string.mt_interval_required));
                        return;
                    }

                    dialog.dismiss();
                    if (creating) {
                        post(activity, Api.MAINTENANCE_TYPE_CREATE,
                                body(null, name, km, months), R.string.mt_created, listener);
                    } else {
                        post(activity, Api.MAINTENANCE_TYPE_UPDATE,
                                body(type.id, renameable ? name : null, km, months),
                                R.string.mt_updated, listener);
                    }
                }));
        dialog.show();
    }

    public static void restoreType(Activity activity, MaintenanceType type, Listener listener) {
        JSONObject json = new JSONObject();
        try {
            json.put("id", type.id);
        } catch (JSONException e) {
            Log.e("Maintenance", "restore body build failed", e);
            return;
        }
        post(activity, Api.MAINTENANCE_TYPE_RESTORE, json, R.string.mt_restored, listener);
    }

    public static void applySuggestion(Activity activity, MaintenanceType type, Listener listener) {
        post(activity, Api.MAINTENANCE_TYPE_UPDATE,
                body(type.id, null,
                        type.suggestedIntervalKm > 0 ? type.suggestedIntervalKm : null,
                        type.suggestedIntervalMonths > 0 ? type.suggestedIntervalMonths : null),
                R.string.mt_updated, listener);
    }

    private static String suggestionText(Activity activity, MaintenanceType type) {
        if (type.custom || (type.suggestedIntervalKm <= 0 && type.suggestedIntervalMonths <= 0)) {
            return "";
        }
        return activity.getString(R.string.mt_suggested, intervalLabel(activity.getResources(),
                type.suggestedIntervalKm, type.suggestedIntervalMonths));
    }

    public static String intervalLabel(android.content.res.Resources res, int km, int months) {
        String kmPart = km > 0 ? formatKm(km) : "";
        String monthsPart = months > 0
                ? res.getQuantityString(R.plurals.months_interval, months, months)
                : "";
        if (!kmPart.isEmpty() && !monthsPart.isEmpty()) {
            return res.getString(R.string.mt_interval_both, kmPart, monthsPart);
        }
        if (!kmPart.isEmpty()) {
            return kmPart;
        }
        if (!monthsPart.isEmpty()) {
            return monthsPart;
        }
        return res.getString(R.string.maint_interval_unknown);
    }

    private static String formatKm(int km) {
        return String.format(Locale.US, "%,d", km).replace(',', ' ') + " км";
    }

    private static Integer parseInterval(String raw, int max) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            int value = Integer.parseInt(trimmed);
            return value > 0 && value <= max ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void hideType(Activity activity, MaintenanceType type, Listener listener) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.mt_hide_title)
                .setMessage(activity.getString(R.string.mt_hide_message, type.name))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.mt_hide, (d, w) -> {
                    JSONObject json = new JSONObject();
                    try {
                        json.put("id", type.id);
                    } catch (JSONException e) {
                        return;
                    }
                    post(activity, Api.MAINTENANCE_TYPE_ARCHIVE, json, R.string.mt_hidden, listener);
                })
                .show();
    }

    private static JSONObject body(@Nullable String id, @Nullable String name,
                                   @Nullable Integer km, @Nullable Integer months) {
        JSONObject json = new JSONObject();
        try {
            if (id != null) json.put("id", id);
            if (name != null) json.put("name", name);
            json.put("intervalKm", km == null ? JSONObject.NULL : km);
            json.put("intervalMonths", months == null ? JSONObject.NULL : months);
        } catch (JSONException e) {
            Log.e("Maintenance", "type body build failed", e);
        }
        return json;
    }

    private static void post(Activity activity, String url, JSONObject json,
                             int successMsg, Listener listener) {
        OkHttpClient client = ApiClient.get(activity);
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json.toString(), JSON))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Maintenance", "POST " + url + " failed", e);
                report(activity, R.string.mt_error, null);
            }

            @Override
            public void onResponse(Call call, Response response) {
                boolean ok;
                int code;
                String error = "";
                try (Response r = response) {
                    ok = r.isSuccessful();
                    code = r.code();
                    if (!ok && r.body() != null) {
                        try {
                            error = new JSONObject(r.body().string()).optString("error", "");
                        } catch (JSONException | IOException e) {
                            Log.w("Maintenance", "unreadable error body", e);
                        }
                    }
                }
                if (ok) {
                    report(activity, successMsg, listener);
                    return;
                }
                int msg = code == 409 || error.contains("name")
                        ? R.string.mt_name_taken
                        : R.string.mt_error;
                report(activity, msg, null);
            }
        });
    }

    private static void report(Activity activity, int msgRes, @Nullable Listener listener) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        activity.runOnUiThread(() -> {
            Toast.makeText(activity, msgRes, Toast.LENGTH_SHORT).show();
            if (listener != null) {
                listener.onCatalogChanged();
            }
        });
    }
}
