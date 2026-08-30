package com.example.maintenanceapp;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.maintenanceapp.model.EngineOption;
import com.example.maintenanceapp.model.OilRecommendation;
import com.example.maintenanceapp.model.Vehicle;
import com.example.maintenanceapp.util.Api;
import com.example.maintenanceapp.util.ApiClient;
import com.example.maintenanceapp.util.ScreenInsets;
import com.example.maintenanceapp.util.VehicleType;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Oil advisor: pick a vehicle + engine code, get the viscosity grade and the OEM approvals the
 * engine requires. Opened from the Поддръжка tab. */
public class OilRecommendationActivity extends AppCompatActivity {

    public static final String EXTRA_VEHICLES = "extra_vehicles";


    private static final int OIL_MAX_ATTEMPTS = 3;
    private static final String PREFS = "oil";
    private static final String KEY_ENGINE_PREFIX = "engine_";

    /** Fuel labels shown to the user */
    private static final String[] FUEL_LABELS = {"Дизел", "Бензин", "Газ (LPG)"};
    private static final String[] FUEL_VALUES = {"diesel", "petrol", "lpg"};

    private OkHttpClient client;
    private List<Vehicle> vehicles = new ArrayList<>();
    private final List<EngineOption> engines = new ArrayList<>();
    private final Map<String, EngineOption> enginesByLabel = new LinkedHashMap<>();
    private int selectedVehicleIndex = -1;
    private TextView txtResultEngine, txtViscosity, txtAlt, txtCapacity, txtInterval, txtNote, productsLabel, error;
    private MaterialAutoCompleteTextView ddVehicle, ddEngine, ddFuel;
    private MaterialCardView cardResult;
    private MaterialButton btnRecommend;
    private TextInputLayout tilEngine;
    private View enginesError;
    private ProgressBar progress;
    private ChipGroup chipSpecs;
    private LinearLayout rowAlt, rowCapacity, rowInterval, productsContainer;
    private boolean requestInFlight;

    @Override
    @SuppressWarnings("unchecked")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_oil_recommendation);

        client = ApiClient.get(this);
        ScreenInsets.apply(findViewById(R.id.oilRoot));

        Object extra = getIntent().getSerializableExtra(EXTRA_VEHICLES);
        if (extra instanceof List) {
            List<Vehicle> passed = (List<Vehicle>) extra;
            List<Vehicle> cars = new ArrayList<>();
            for (Vehicle v : passed) {
                if (VehicleType.of(v).supportsOilAdvisor()) {
                    cars.add(v);
                }
            }
            vehicles = cars;
        }

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        ddVehicle = findViewById(R.id.ddVehicle);
        ddEngine = findViewById(R.id.ddEngine);
        ddFuel = findViewById(R.id.ddFuel);
        tilEngine = findViewById(R.id.tilEngine);
        enginesError = findViewById(R.id.oilEnginesError);
        btnRecommend = findViewById(R.id.btnRecommend);
        progress = findViewById(R.id.oilProgress);
        error = findViewById(R.id.oilError);

        cardResult = findViewById(R.id.cardResult);
        txtResultEngine = findViewById(R.id.txtResultEngine);
        txtViscosity = findViewById(R.id.txtViscosity);
        chipSpecs = findViewById(R.id.chipSpecs);
        rowAlt = findViewById(R.id.rowAlt);
        txtAlt = findViewById(R.id.txtAlt);
        rowCapacity = findViewById(R.id.rowCapacity);
        txtCapacity = findViewById(R.id.txtCapacity);
        rowInterval = findViewById(R.id.rowInterval);
        txtInterval = findViewById(R.id.txtInterval);
        productsLabel = findViewById(R.id.productsLabel);
        productsContainer = findViewById(R.id.productsContainer);
        txtNote = findViewById(R.id.txtOilNote);

        ddFuel.setSimpleItems(FUEL_LABELS);

        String[] vehicleLabels = new String[vehicles.size()];
        for (int i = 0; i < vehicles.size(); i++) {
            vehicleLabels[i] = vehicleLabel(vehicles.get(i));
        }
        ddVehicle.setSimpleItems(vehicleLabels);
        ddVehicle.setOnItemClickListener((parent, view, pos, id) -> selectVehicle(pos));
        ddEngine.setOnItemClickListener((parent, view, pos, id) -> {
            tilEngine.setError(null);
            hideResult();
        });

        findViewById(R.id.btnRetryEngines).setOnClickListener(v -> {
            enginesError.setVisibility(View.GONE);
            fetchEngines(1);
        });

        btnRecommend.setOnClickListener(v -> recommend());

        if (!vehicles.isEmpty()) {
            selectVehicle(0);
        } else {
            btnRecommend.setEnabled(false);
            showError(getString(extra instanceof List && !((List<?>) extra).isEmpty()
                    ? R.string.oil_no_cars
                    : R.string.maint_no_vehicles));
        }

        fetchEngines(1);
    }

    private String vehicleLabel(Vehicle v) {
        String name = ((v.make == null ? "" : v.make) + " " + (v.model == null ? "" : v.model)).trim();
        if (v.licensePlate != null && !v.licensePlate.isEmpty()) {
            name = name + " — " + v.licensePlate;
        }
        return name.isEmpty() ? "—" : name;
    }

    private void selectVehicle(int pos) {
        if (pos < 0 || pos >= vehicles.size()) {
            return;
        }
        selectedVehicleIndex = pos;
        Vehicle v = vehicles.get(pos);
        ddVehicle.setText(vehicleLabel(v), false);   // false = don't re-filter

        hideResult();
        applyFuelFor(v);
        applyEngineItems();
        String remembered = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_ENGINE_PREFIX + (v.id == null ? "" : v.id), "");
        ddEngine.setText(remembered, false);
        tilEngine.setError(null);
    }

    private void applyFuelFor(Vehicle v) {
        String fuel = v.fuelType == null ? "" : v.fuelType.trim().toLowerCase(Locale.ROOT);

        if (fuel.startsWith("electric") || fuel.startsWith("ел")) {
            ddFuel.setText("", false);
            btnRecommend.setEnabled(false);
            showError(getString(R.string.oil_electric));
            return;
        }

        btnRecommend.setEnabled(true);
        hideError();

        int index = 0;   // diesel — the majority case, and what an unrecognised value falls back to
        if (fuel.startsWith("petrol") || fuel.startsWith("gasoline") || fuel.startsWith("бензин")
                || fuel.startsWith("hybrid")) {
            index = 1;   // a hybrid's combustion half is a petrol engine
        } else if (fuel.startsWith("lpg") || fuel.contains("газ")) {
            index = 2;
        }
        ddFuel.setText(FUEL_LABELS[index], false);
    }

    private String selectedFuelValue() {
        String label = ddFuel.getText() == null ? "" : ddFuel.getText().toString();
        for (int i = 0; i < FUEL_LABELS.length; i++) {
            if (FUEL_LABELS[i].equals(label)) {
                return FUEL_VALUES[i];
            }
        }
        return "";
    }

    /** Loads the engine catalog. */
    private void fetchEngines(int attempt) {
        Request request = new Request.Builder().url(Api.OIL_ENGINES).get().build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Oil", "GET /oil/engines failed (attempt " + attempt + ")", e);
                retryOrGiveUp(attempt);
            }

            @Override
            public void onResponse(Call call, Response response) {
                List<EngineOption> parsed = new ArrayList<>();
                boolean ok = false;
                try (Response r = response) {
                    if (r.isSuccessful() && r.body() != null) {
                        JSONArray arr = new JSONObject(r.body().string()).optJSONArray("engines");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject o = arr.optJSONObject(i);
                                if (o == null) continue;
                                EngineOption e = EngineOption.fromJson(o);
                                if (!e.code.isEmpty()) parsed.add(e);
                            }
                            ok = true;
                        }
                    } else {
                        Log.w("Oil", "GET /oil/engines -> HTTP " + r.code());
                    }
                } catch (IOException | JSONException e) {
                    Log.e("Oil", "engines parse failed (attempt " + attempt + ")", e);
                }

                if (!ok) {
                    retryOrGiveUp(attempt);
                    return;
                }
                final List<EngineOption> result = parsed;
                runOnUiThread(() -> {
                    engines.clear();
                    engines.addAll(result);
                    enginesError.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
                    applyEngineItems();
                });
            }
        });
    }

    private void retryOrGiveUp(int attempt) {
        if (attempt < OIL_MAX_ATTEMPTS) {
            fetchEngines(attempt + 1);
            return;
        }
        runOnUiThread(() -> enginesError.setVisibility(View.VISIBLE));
    }

    private void applyEngineItems() {
        if (ddEngine == null) {
            return;
        }
        String make = selectedVehicleIndex >= 0 && selectedVehicleIndex < vehicles.size()
                ? vehicles.get(selectedVehicleIndex).make
                : null;

        List<EngineOption> ordered = new ArrayList<>();
        for (EngineOption e : engines) {
            if (e.fitsMake(make)) ordered.add(e);
        }
        for (EngineOption e : engines) {
            if (!e.fitsMake(make)) ordered.add(e);
        }

        enginesByLabel.clear();
        String[] labels = new String[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            labels[i] = ordered.get(i).label();
            enginesByLabel.put(labels[i], ordered.get(i));
        }

        String typed = ddEngine.getText() == null ? "" : ddEngine.getText().toString();
        ddEngine.setSimpleItems(labels);
        ddEngine.setText(typed, false);
    }

    /** The engine code to send. */
    private String engineCodeToSend() {
        String text = ddEngine.getText() == null ? "" : ddEngine.getText().toString().trim();
        if (text.isEmpty()) {
            return "";
        }
        EngineOption picked = enginesByLabel.get(text);
        if (picked != null) {
            return picked.code;
        }

        int open = text.lastIndexOf('(');
        int close = text.lastIndexOf(')');
        if (open >= 0 && close > open) {
            String inner = text.substring(open + 1, close).trim();
            if (!inner.isEmpty()) {
                return inner.toUpperCase(Locale.US);
            }
        }
        return text.toUpperCase(Locale.US);
    }

    /* recommend motor oil. */
    private void recommend() {
        if (requestInFlight) {
            return;
        }
        String code = engineCodeToSend();
        if (code.isEmpty()) {
            tilEngine.setError(getString(R.string.oil_no_engine));
            ddEngine.requestFocus();
            return;
        }
        tilEngine.setError(null);

        Vehicle vehicle = selectedVehicleIndex >= 0 && selectedVehicleIndex < vehicles.size()
                ? vehicles.get(selectedVehicleIndex)
                : null;

        HttpUrl base = HttpUrl.parse(Api.OIL_RECOMMEND);
        if (base == null) {
            showError(getString(R.string.oil_error));
            return;
        }
        HttpUrl.Builder url = base.newBuilder()
                .addQueryParameter("engineCode", code)
                .addQueryParameter("fuelType", selectedFuelValue());

        if (vehicle != null && vehicle.mileage > 0) {
            url.addQueryParameter("mileage", String.valueOf(vehicle.mileage));
        }

        hideResult();
        hideError();
        setLoading(true);
        fetchRecommendation(url.build(), code, 1);
    }

    private void fetchRecommendation(HttpUrl url, String code, int attempt) {
        Request request = new Request.Builder().url(url).get().build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Oil", "GET /oil/recommend failed (attempt " + attempt + ")", e);
                if (attempt < OIL_MAX_ATTEMPTS) {
                    fetchRecommendation(url, code, attempt + 1);
                    return;
                }
                runOnUiThread(() -> {
                    setLoading(false);
                    showError(getString(R.string.oil_error));
                });
            }

            @Override
            public void onResponse(Call call, Response response) {
                OilRecommendation parsed = null;
                boolean notFound = false;
                boolean transportFailure = false;
                try (Response r = response) {
                    if (r.code() == 404) {
                        notFound = true;
                    } else if (r.isSuccessful() && r.body() != null) {
                        parsed = OilRecommendation.fromJson(new JSONObject(r.body().string()));
                    } else {
                        Log.w("Oil", "GET /oil/recommend -> HTTP " + r.code());
                        transportFailure = true;
                    }
                } catch (IOException | JSONException e) {
                    Log.e("Oil", "recommendation parse failed (attempt " + attempt + ")", e);
                    transportFailure = true;
                }

                if (transportFailure && attempt < OIL_MAX_ATTEMPTS) {
                    fetchRecommendation(url, code, attempt + 1);
                    return;
                }

                final OilRecommendation result = parsed;
                final boolean unknownEngine = notFound;
                runOnUiThread(() -> {
                    setLoading(false);
                    if (unknownEngine) {
                        showError(getString(R.string.oil_not_found));
                        return;
                    }

                    if (result == null || !result.isUsable()) {
                        showError(getString(result == null ? R.string.oil_error : R.string.oil_not_found));
                        return;
                    }
                    render(result, code);
                });
            }
        });
    }

    /** Renders the answer and remembers the engine that produced it for this vehicle. */
    private void render(OilRecommendation r, String code) {
        String name = r.engineName.isEmpty() ? code : r.engineName + " (" + code + ")";
        txtResultEngine.setText(getString(R.string.oil_engine_label, name));

        txtViscosity.setText(r.viscosity.isEmpty() ? "—" : r.viscosity);

        rowAlt.setVisibility(r.altViscosity.isEmpty() ? View.GONE : View.VISIBLE);
        txtAlt.setText(r.altViscosity);

        chipSpecs.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (String spec : r.specs) {
            Chip chip = (Chip) inflater.inflate(R.layout.item_oil_spec, chipSpecs, false);
            chip.setText(spec);
            chipSpecs.addView(chip);
        }
        chipSpecs.setVisibility(r.specs.isEmpty() ? View.GONE : View.VISIBLE);

        if (r.capacityLiters > 0) {
            rowCapacity.setVisibility(View.VISIBLE);
            txtCapacity.setText(getString(R.string.oil_capacity_value, formatLiters(r.capacityLiters)));
        } else {
            rowCapacity.setVisibility(View.GONE);
        }

        if (r.intervalKm > 0) {
            rowInterval.setVisibility(View.VISIBLE);
            String km = String.format(Locale.getDefault(), "%,d", r.intervalKm);
            txtInterval.setText(r.intervalMonths > 0
                    ? getString(R.string.oil_interval_both, km, r.intervalMonths)
                    : getString(R.string.oil_interval_km, km));
        } else {
            rowInterval.setVisibility(View.GONE);
        }

        productsContainer.removeAllViews();
        for (OilRecommendation.Product p : r.products) {
            View row = inflater.inflate(R.layout.item_oil_product, productsContainer, false);
            TextView title = row.findViewById(R.id.txtProductName);
            TextView specs = row.findViewById(R.id.txtProductSpecs);
            title.setText(p.viscosity.isEmpty() ? p.name : p.name + " " + p.viscosity);
            if (p.specs.isEmpty()) {
                specs.setVisibility(View.GONE);
            } else {
                specs.setVisibility(View.VISIBLE);
                specs.setText(p.specs);
            }
            productsContainer.addView(row);
        }
        productsLabel.setVisibility(r.products.isEmpty() ? View.GONE : View.VISIBLE);

        txtNote.setVisibility(r.note.isEmpty() ? View.GONE : View.VISIBLE);
        txtNote.setText(r.note);

        cardResult.setVisibility(View.VISIBLE);

        if (selectedVehicleIndex >= 0 && selectedVehicleIndex < vehicles.size()) {
            Vehicle v = vehicles.get(selectedVehicleIndex);
            if (v.id != null && !v.id.isEmpty()) {
                SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
                editor.putString(KEY_ENGINE_PREFIX + v.id, ddEngine.getText().toString().trim());
                editor.apply();
            }
        }
    }

    /** 4.6 stays 4.6; 5.0 prints as 5 — a trailing zero on a litre figure reads as false precision. */
    private String formatLiters(double liters) {
        if (Math.abs(liters - Math.rint(liters)) < 0.05) {
            return String.valueOf((int) Math.rint(liters));
        }
        return String.format(Locale.getDefault(), "%.1f", liters);
    }

    // ---- state ---------------------------------------------------------------------------------

    private void setLoading(boolean loading) {
        requestInFlight = loading;
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnRecommend.setEnabled(!loading);
    }

    private void showError(String message) {
        error.setText(message);
        error.setVisibility(View.VISIBLE);
        cardResult.setVisibility(View.GONE);
    }

    private void hideError() {
        error.setVisibility(View.GONE);
    }

    private void hideResult() {
        cardResult.setVisibility(View.GONE);
        hideError();
    }
}
