/*
 * VehicleComplianceActivity.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

package com.example.maintenanceapp;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.maintenanceapp.model.Vehicle;
import com.example.maintenanceapp.model.VignetteInfo;
import com.example.maintenanceapp.util.Api;
import com.example.maintenanceapp.util.ApiClient;
import com.example.maintenanceapp.util.ComplianceStatus;
import com.example.maintenanceapp.util.ScreenInsets;
import com.example.maintenanceapp.util.SwipeRefresh;
import com.example.maintenanceapp.util.VehicleType;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONException;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class VehicleComplianceActivity extends AppCompatActivity {

    public static final String EXTRA_VEHICLE = "extra_vehicle";
    public static final String EXTRA_RESULT_VEHICLE = "extra_result_vehicle";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int VIGNETTE_MAX_ATTEMPTS = 3;
    private static final String URL_BUY_VIGNETTE = "https://web.bgtoll.bg/";
    private static final String URL_CHECK_VIGNETTE = "https://check.bgtoll.bg/#/vignette";
    private static final String URL_CHECK_INSPECTION = "https://public-eis.rta.government.bg/public-vehicle-check/vin-check";
    private static final String HOST_INSPECTION = "public-eis.rta.government.bg";
    private static final String URL_CHECK_INSURANCE =
            "https://www.guaranteefund.org/bg/%D0%B8%D0%BD%D1%84%D0%BE%D1%80%D0%BC%D0%B0%D1%86%D0%B8%D0%BE%D0%BD%D0%B5%D0%BD-%D1%86%D0%B5%D0%BD%D1%82%D1%8A%D1%80-%D0%B8-%D1%81%D0%BF%D1%80%D0%B0%D0%B2%D0%BA%D0%B8/%D1%83%D1%81%D0%BB%D1%83%D0%B3%D0%B8/%D0%BF%D1%80%D0%BE%D0%B2%D0%B5%D1%80%D0%BA%D0%B0-%D0%B7%D0%B0-%D0%B2%D0%B0%D0%BB%D0%B8%D0%B4%D0%BD%D0%B0-%D0%B7%D0%B0%D1%81%D1%82%D1%80%D0%B0%D1%85%D0%BE%D0%B2%D0%BA%D0%B0-%D0%B3%D1%80a%D0%B6%D0%B4a%D0%BD%D1%81%D0%BAa-%D0%BE%D1%82%D0%B3%D0%BE%D0%B2%D0%BE%D1%80%D0%BD%D0%BE%D1%81%D1%82-%D0%BD%D0%B0-%D0%B0%D0%B2%D1%82%D0%BE%D0%BC%D0%BE%D0%B1%D0%B8%D0%BB%D0%B8%D1%81%D1%82%D0%B8%D1%82%D0%B5";

    private OkHttpClient client;
    private Vehicle vehicle;
    private SwipeRefreshLayout swipe;
    @Nullable
    private VignetteInfo vignette;
    private boolean vignetteFailed;
    private boolean vignetteLoading;
    private boolean saveInFlight;

    private final ActivityResultLauncher<Intent> vinCheckLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    return;
                }
                String iso = result.getData().getStringExtra(VinCheckActivity.EXTRA_RESULT_DATE);
                if (iso == null || iso.isEmpty()) {
                    return;
                }
                String previous = vehicle.inspectionValidTo;
                vehicle.inspectionValidTo = iso;
                persist(() -> vehicle.inspectionValidTo = previous,
                        getString(R.string.cmp_checked_saved, ComplianceStatus.format(iso)));
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vehicle_compliance);

        client = ApiClient.get(this);

        ScreenInsets.apply(findViewById(R.id.cmpRoot));

        vehicle = (Vehicle) getIntent().getSerializableExtra(EXTRA_VEHICLE);
        if (vehicle == null) {
            finish();
            return;
        }

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        ((TextView) findViewById(R.id.cmpVehicle)).setText(vehicleLabel());

        swipe = findViewById(R.id.cmpRoot);
        SwipeRefresh.theme(swipe);
        swipe.setOnRefreshListener(this::fetchVignette);

        bindAll();
        fetchVignette();
    }

    private String vehicleLabel() {
        String name = ((vehicle.make == null ? "" : vehicle.make) + " "
                + (vehicle.model == null ? "" : vehicle.model)).trim();
        if (vehicle.licensePlate != null && !vehicle.licensePlate.isEmpty()) {
            name = name.isEmpty() ? vehicle.licensePlate : name + " — " + vehicle.licensePlate;
        }
        return name;
    }

    private void bindAll() {
        bindVignetteCard();
        bindInspectionCard();
        bindInsuranceCard();
    }

    private void bindVignetteCard() {
        View card = findViewById(R.id.cardVignette);
        chip(card, R.drawable.ic_road, 0);
        title(card, R.string.cmp_doc_vignette);

        TextView value = card.findViewById(R.id.cmpValue);
        TextView relative = card.findViewById(R.id.cmpRelative);
        TextView source = card.findViewById(R.id.cmpSource);
        source.setText(R.string.cmp_src_official);
        source.setVisibility(View.VISIBLE);

        MaterialButton primary = card.findViewById(R.id.cmpBtnPrimary);
        MaterialButton secondary = card.findViewById(R.id.cmpBtnSecondary);

        if (!VehicleType.of(vehicle).requiresVignette()) {
            source.setVisibility(View.GONE);
            statusPill(card, null, R.string.cmp_st_exempt);
            value.setText(R.string.cmp_vignette_exempt);
            relative.setVisibility(View.VISIBLE);
            relative.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
            relative.setText(R.string.cmp_vignette_exempt_hint);
            details(card, null);
            primary.setVisibility(View.GONE);
            secondary.setVisibility(View.GONE);
            card.findViewById(R.id.cmpRenewRow).setVisibility(View.GONE);
            return;
        }

        primary.setVisibility(View.VISIBLE);
        primary.setText(R.string.cmp_buy_vignette);
        primary.setIconResource(R.drawable.ic_open_in_new);
        primary.setOnClickListener(v -> openExternal(URL_BUY_VIGNETTE,
                vehicle.licensePlate, R.string.cmp_plate_copied));
        secondary.setVisibility(View.GONE);
        card.findViewById(R.id.cmpRenewRow).setVisibility(View.GONE);

        if (vignetteLoading) {
            statusPill(card, null, R.string.cmp_st_unknown);
            value.setText(R.string.cmp_checking);
            relative.setVisibility(View.GONE);
            details(card, null);
            return;
        }

        if (vignetteFailed || vignette == null) {
            statusPill(card, null, R.string.cmp_st_unknown);
            value.setText(R.string.cmp_unavailable);
            relative.setVisibility(View.VISIBLE);
            relative.setTextColor(ContextCompat.getColor(this, R.color.status_due_text));
            relative.setText(R.string.cmp_unavailable_hint);
            details(card, null);
            secondary.setVisibility(View.VISIBLE);
            secondary.setText(R.string.cmp_check_official);
            secondary.setIconResource(R.drawable.ic_open_in_new);
            secondary.setOnClickListener(v -> openExternal(URL_CHECK_VIGNETTE, vehicle.licensePlate, R.string.cmp_plate_copied));
            return;
        }

        if (!vignette.isValid()) {
            statusPill(card, ComplianceStatus.OVERDUE, R.string.cmp_st_none);
            value.setText(R.string.cmp_no_vignette);
            relative.setVisibility(View.GONE);
            details(card, checkedAtDetail());
            return;
        }

        ComplianceStatus status = ComplianceStatus.of(vignette.validTo, ComplianceStatus.VIGNETTE_DUE_DAYS);
        statusPill(card, status, status == null ? R.string.cmp_st_unknown : status.labelRes);
        bindDate(card, vignette.validTo);

        List<String> facts = new ArrayList<>();
        if (vignette.number != null) {
            facts.add(getString(R.string.cmp_detail_number, vignette.number));
        }
        if (vignette.vehicleClass != null) {
            facts.add(getString(R.string.cmp_detail_class, vignette.vehicleClass));
        }
        if (vignette.emissionsClass != null) {
            facts.add(getString(R.string.cmp_detail_emissions, vignette.emissionsClass));
        }
        String from = ComplianceStatus.format(vignette.validFrom);
        if (from != null) {
            facts.add(getString(R.string.cmp_detail_from, from));
        }
        String checked = checkedAtDetail();
        if (checked != null) {
            facts.add(checked);
        }
        details(card, facts.isEmpty() ? null : join(facts));
    }

    private void bindInspectionCard() {
        View card = findViewById(R.id.cardInspection);
        chip(card, R.drawable.ic_inspection, 2);
        title(card, R.string.cmp_doc_inspection);
        ((TextView) card.findViewById(R.id.cmpSource)).setText(R.string.cmp_src_declared);

        ComplianceStatus status = ComplianceStatus.of(vehicle.inspectionValidTo, ComplianceStatus.INSPECTION_DUE_DAYS);
        statusPill(card, status, status == null ? R.string.cmp_st_unknown : status.labelRes);
        bindDate(card, vehicle.inspectionValidTo);
        details(card, null);

        MaterialButton primary = card.findViewById(R.id.cmpBtnPrimary);
        primary.setVisibility(View.VISIBLE);
        primary.setText(R.string.cmp_set_date);
        primary.setIconResource(R.drawable.ic_calendar);
        primary.setOnClickListener(v -> pickDate(R.string.cmp_pick_inspection,
                vehicle.inspectionValidTo, iso -> vehicle.inspectionValidTo = iso));

        MaterialButton secondary = card.findViewById(R.id.cmpBtnSecondary);
        secondary.setVisibility(View.VISIBLE);
        secondary.setText(R.string.cmp_check_official);
        secondary.setOnClickListener(v -> openInspectionCheck());

        bindRenewRow(card, status, R.string.cmp_renew_q_inspection,
                () -> vehicle.inspectionValidTo,
                iso -> vehicle.inspectionValidTo = iso);
    }

    private void bindInsuranceCard() {
        View card = findViewById(R.id.cardInsurance);
        chip(card, R.drawable.ic_shield, 4);               // accent_5 (rose)
        title(card, R.string.cmp_doc_insurance);
        ((TextView) card.findViewById(R.id.cmpSource)).setText(R.string.cmp_src_declared);
        ComplianceStatus policy = ComplianceStatus.of(vehicle.insuranceValidTo, ComplianceStatus.INSURANCE_DUE_DAYS);
        ComplianceStatus instalment =
                ComplianceStatus.of(vehicle.insuranceNextInstallment, ComplianceStatus.INSTALLMENT_DUE_DAYS);
        ComplianceStatus worst = ComplianceStatus.worst(policy, instalment);
        statusPill(card, worst, worst == null ? R.string.cmp_st_unknown : worst.labelRes);

        bindDate(card, vehicle.insuranceValidTo);

        String nextInstalment = ComplianceStatus.format(vehicle.insuranceNextInstallment);
        details(card, nextInstalment == null
                ? getString(R.string.cmp_detail_installment_none)
                : getString(R.string.cmp_detail_installment, nextInstalment));

        MaterialButton primary = card.findViewById(R.id.cmpBtnPrimary);
        primary.setVisibility(View.VISIBLE);
        primary.setText(R.string.cmp_set_date);
        primary.setIconResource(R.drawable.ic_calendar);
        primary.setOnClickListener(v -> chooseInsuranceDate());

        MaterialButton secondary = card.findViewById(R.id.cmpBtnSecondary);
        secondary.setVisibility(View.VISIBLE);
        secondary.setText(R.string.cmp_check_official);
        secondary.setOnClickListener(v -> openInsuranceCheck());
        bindRenewRow(card, policy, R.string.cmp_renew_q_insurance,
                () -> vehicle.insuranceValidTo,
                iso -> vehicle.insuranceValidTo = iso);
    }

    private interface DateSource {
        String get();
    }

    private void bindRenewRow(View card, ComplianceStatus status, int questionRes,
                              DateSource source, DateSink sink) {
        View row = card.findViewById(R.id.cmpRenewRow);
        if (status != ComplianceStatus.DUE && status != ComplianceStatus.OVERDUE) {
            row.setVisibility(View.GONE);
            return;
        }
        row.setVisibility(View.VISIBLE);
        ((TextView) card.findViewById(R.id.cmpRenewQuestion)).setText(questionRes);
        card.findViewById(R.id.cmpBtnRenew).setOnClickListener(v -> renew(source, sink));
    }

    private void renew(DateSource source, DateSink sink) {
        String previousInspection = vehicle.inspectionValidTo;
        String previousInsurance = vehicle.insuranceValidTo;
        String previousInstalment = vehicle.insuranceNextInstallment;

        String renewed = ComplianceStatus.plusOneYear(source.get());
        sink.accept(renewed);
        persist(() -> {
            vehicle.inspectionValidTo = previousInspection;
            vehicle.insuranceValidTo = previousInsurance;
            vehicle.insuranceNextInstallment = previousInstalment;
        }, getString(R.string.cmp_renewed, ComplianceStatus.format(renewed)));
    }

    private void bindDate(View card, String isoDate) {
        TextView value = card.findViewById(R.id.cmpValue);
        TextView relative = card.findViewById(R.id.cmpRelative);

        String formatted = ComplianceStatus.format(isoDate);
        if (formatted == null) {
            value.setText(R.string.cmp_no_date);
            relative.setVisibility(View.GONE);
            return;
        }
        value.setText(formatted);

        Integer days = ComplianceStatus.daysUntil(isoDate);
        if (days == null) {
            relative.setVisibility(View.GONE);
            return;
        }
        relative.setVisibility(View.VISIBLE);
        if (days == 0) {
            relative.setText(R.string.cmp_expires_today);
        } else if (days > 0) {
            relative.setText(getResources().getQuantityString(R.plurals.cmp_days_left, days, days));
        } else {
            relative.setText(getResources()
                    .getQuantityString(R.plurals.cmp_days_overdue, -days, -days));
        }
        ComplianceStatus s = days < 0 ? ComplianceStatus.OVERDUE : ComplianceStatus.OK;
        relative.setTextColor(ContextCompat.getColor(this, s.textColorRes));
    }

    private void chip(View card, int iconRes, int accentIndex) {
        ImageView icon = card.findViewById(R.id.cmpIcon);
        int[] fg = getResources().getIntArray(R.array.accent_fg);
        int[] bg = getResources().getIntArray(R.array.accent_bg);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(fg[accentIndex % fg.length]));
        icon.setBackgroundTintList(ColorStateList.valueOf(bg[accentIndex % bg.length]));
    }

    private void title(View card, int titleRes) {
        ((TextView) card.findViewById(R.id.cmpTitle)).setText(titleRes);
    }

    private void statusPill(View card, @Nullable ComplianceStatus status, int labelRes) {
        TextView pill = card.findViewById(R.id.cmpStatus);
        pill.setText(labelRes);
        if (status == null) {
            pill.setBackgroundTintList(ColorStateList.valueOf(
                    themeColor(com.google.android.material.R.attr.colorOutlineVariant)));
            pill.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        } else {
            pill.setBackgroundTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(this, status.colorRes)));
            pill.setTextColor(ContextCompat.getColor(this, R.color.white));
        }
    }

    private void details(View card, @Nullable String text) {
        TextView details = card.findViewById(R.id.cmpDetails);
        if (text == null || text.isEmpty()) {
            details.setVisibility(View.GONE);
        } else {
            details.setVisibility(View.VISIBLE);
            details.setText(text);
        }
    }

    private int themeColor(int attr) {
        TypedValue tv = new TypedValue();
        if (!getTheme().resolveAttribute(attr, tv, true)) {
            return ContextCompat.getColor(this, R.color.status_due);   // role missing: visible, not crashy
        }
        return tv.resourceId != 0 ? ContextCompat.getColor(this, tv.resourceId) : tv.data;
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    @Nullable
    private String checkedAtDetail() {
        if (vignette == null || vignette.checkedAt == null) {
            return null;
        }
        String raw = vignette.checkedAt.trim();
        int dot = raw.indexOf('.');
        if (dot > 0) {
            raw = raw.substring(0, dot);   // drop fractional seconds
        }
        raw = raw.replace("Z", "");
        if (raw.length() > 19) {
            raw = raw.substring(0, 19);    // drop a trailing offset
        }
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        in.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Date d = in.parse(raw);
            if (d == null) {
                return null;
            }
            return getString(R.string.cmp_detail_checked,
                    new SimpleDateFormat("dd.MM HH:mm", Locale.US).format(d));
        } catch (ParseException e) {
            return null;   // better no line at all than a raw timestamp
        }
    }

    private interface DateSink {
        void accept(String iso);
    }

    private void chooseInsuranceDate() {
        String[] options = {
                getString(R.string.cmp_pick_insurance),
                getString(R.string.cmp_pick_installment),
                getString(R.string.cmp_clear_date),
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cmp_which_date)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        pickDate(R.string.cmp_pick_insurance, vehicle.insuranceValidTo,
                                iso -> vehicle.insuranceValidTo = iso);
                    } else if (which == 1) {
                        pickDate(R.string.cmp_pick_installment, vehicle.insuranceNextInstallment,
                                iso -> vehicle.insuranceNextInstallment = iso);
                    } else {
                        String previous = vehicle.insuranceNextInstallment;
                        vehicle.insuranceNextInstallment = "";
                        persist(() -> vehicle.insuranceNextInstallment = previous);
                    }
                })
                .show();
    }

    private void pickDate(int titleRes, @Nullable String current, DateSink sink) {
        MaterialDatePicker.Builder<Long> builder = MaterialDatePicker.Builder.datePicker()
                .setTitleText(titleRes);
        Long selection = isoToUtcMillis(current);
        if (selection != null) {
            builder.setSelection(selection);
        }
        MaterialDatePicker<Long> picker = builder.build();
        picker.addOnPositiveButtonClickListener(millis -> {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            String previousInspection = vehicle.inspectionValidTo;
            String previousInsurance = vehicle.insuranceValidTo;
            String previousInstalment = vehicle.insuranceNextInstallment;
            sink.accept(fmt.format(new Date(millis)));
            persist(() -> {
                // Restores all three rather than tracking which one the sink wrote: the whole vehicle
                // is what gets POSTed, so the whole vehicle is what has to roll back.
                vehicle.inspectionValidTo = previousInspection;
                vehicle.insuranceValidTo = previousInsurance;
                vehicle.insuranceNextInstallment = previousInstalment;
            });
        });
        picker.show(getSupportFragmentManager(), "cmp_date_picker");
    }

    @Nullable
    private static Long isoToUtcMillis(@Nullable String iso) {
        if (iso == null || iso.trim().isEmpty()) {
            return null;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        fmt.setLenient(false);
        try {
            Date d = fmt.parse(iso.trim());
            return d == null ? null : d.getTime();
        } catch (ParseException e) {
            return null;
        }
    }

    private void persist(Runnable rollback) {
        persist(rollback, getString(R.string.cmp_saved));
    }

    private void persist(Runnable rollback, String successMessage) {
        if (saveInFlight) {
            return;
        }
        bindAll();   // optimistic: the user sees their date land straight away

        String body;
        try {
            body = vehicle.toUpdateJson().toString();
        } catch (JSONException e) {
            rollback.run();
            bindAll();
            Toast.makeText(this, R.string.cmp_save_error, Toast.LENGTH_SHORT).show();
            return;
        }

        saveInFlight = true;
        Request request = new Request.Builder()
                .url(Api.VEHICLE_UPDATE)
                .post(RequestBody.create(body, JSON))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Compliance", "POST /vehicles/update failed", e);
                runOnUiThread(() -> {
                    saveInFlight = false;
                    rollback.run();
                    bindAll();
                    Toast.makeText(VehicleComplianceActivity.this,
                            R.string.cmp_save_error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) {
                boolean ok;
                try (Response r = response) {
                    ok = r.isSuccessful();
                }
                runOnUiThread(() -> {
                    saveInFlight = false;
                    if (!ok) {
                        rollback.run();
                        bindAll();
                        Toast.makeText(VehicleComplianceActivity.this,
                                R.string.cmp_save_error, Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(VehicleComplianceActivity.this,
                            successMessage, Toast.LENGTH_SHORT).show();

                    Intent data = new Intent();
                    data.putExtra(EXTRA_RESULT_VEHICLE, vehicle);
                    setResult(RESULT_OK, data);
                });
            }
        });
    }

    private void fetchVignette() {
        if (!VehicleType.of(vehicle).requiresVignette()) {
            vignetteLoading = false;
            vignetteFailed = false;
            vignette = null;
            if (swipe != null) {
                swipe.setRefreshing(false);
            }
            bindVignetteCard();
            return;
        }
        if (vehicle.id == null || vehicle.id.isEmpty()) {
            deliverVignette(null, true);
            return;
        }
        vignetteLoading = true;
        vignetteFailed = false;
        bindVignetteCard();
        fetchVignette(1);
    }

    private void fetchVignette(int attempt) {
        HttpUrl url = HttpUrl.parse(Api.VEHICLE_VIGNETTE).newBuilder()
                .addQueryParameter("id", vehicle.id)
                .build();
        Request request = new Request.Builder().url(url).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Compliance", "GET /vehicles/vignette failed (attempt " + attempt + ")", e);
                if (attempt < VIGNETTE_MAX_ATTEMPTS) {
                    fetchVignette(attempt + 1);
                    return;
                }
                deliverVignette(null, true);
            }

            @Override
            public void onResponse(Call call, Response response) {
                VignetteInfo info = null;
                boolean retriable = false;
                try (Response r = response) {
                    if (r.isSuccessful() && r.body() != null) {
                        info = VignetteInfo.fromJson(r.body().string());
                    }
                } catch (IOException e) {
                    retriable = true;   // truncated body — worth another attempt
                    Log.e("Compliance", "vignette read failed (attempt " + attempt + ")", e);
                } catch (JSONException e) {
                    Log.e("Compliance", "vignette parse failed", e);
                }

                if (info == null) {
                    if (retriable && attempt < VIGNETTE_MAX_ATTEMPTS) {
                        fetchVignette(attempt + 1);
                        return;
                    }
                    deliverVignette(null, true);
                    return;
                }
                deliverVignette(info, false);
            }
        });
    }

    private void deliverVignette(@Nullable VignetteInfo info, boolean failed) {
        runOnUiThread(() -> {
            vignetteLoading = false;
            vignette = info;
            vignetteFailed = failed;
            if (swipe != null) {
                swipe.setRefreshing(false);
            }
            bindVignetteCard();
        });
    }

    private void openInspectionCheck() {
        if (isBlank(vehicle.vin)) {
            openExternal(URL_CHECK_INSPECTION, null, R.string.cmp_vin_copied);
            return;
        }
        launchCheck(URL_CHECK_INSPECTION, HOST_INSPECTION, vehicle.vin,
                new String[]{
                        "input[formcontrolname*=\"vin\" i]",
                        "input[name*=\"vin\" i]",
                        "input[id*=\"vin\" i]",
                        "input[placeholder*=\"\u0440\u0430\u043c\" i]",
                        "input[placeholder*=\"vin\" i]",
                },
                getString(R.string.vc_title_inspection),
                getString(R.string.vc_hint_inspection));
    }

    private void openInsuranceCheck() {
        if (!isBlank(vehicle.vin)) {
            openExternal(URL_CHECK_INSURANCE, vehicle.vin, R.string.cmp_vin_copied);
        } else {
            openExternal(URL_CHECK_INSURANCE, vehicle.licensePlate, R.string.cmp_plate_copied);
        }
    }

    private void launchCheck(String url, String host, String fillValue, String[] selectors,
                             String title, String hint) {
        Intent intent = new Intent(this, VinCheckActivity.class);
        intent.putExtra(VinCheckActivity.EXTRA_URL, url);
        intent.putExtra(VinCheckActivity.EXTRA_ALLOWED_HOST, host);
        intent.putExtra(VinCheckActivity.EXTRA_FILL_VALUE, fillValue);
        intent.putExtra(VinCheckActivity.EXTRA_FILL_SELECTORS, selectors);
        intent.putExtra(VinCheckActivity.EXTRA_TITLE, title);
        intent.putExtra(VinCheckActivity.EXTRA_HINT, hint);
        vinCheckLauncher.launch(intent);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private void openExternal(String url, String copyValue, int copiedMsgRes) {
        if (copyValue != null && !copyValue.trim().isEmpty()) {
            String value = copyValue.trim();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(value, value));
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this, getString(copiedMsgRes, value), Toast.LENGTH_SHORT).show();
                }
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.cmp_no_browser, Toast.LENGTH_SHORT).show();
        }
    }
}
