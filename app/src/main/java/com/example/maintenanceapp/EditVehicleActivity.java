package com.example.maintenanceapp;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.maintenanceapp.model.Vehicle;
import com.example.maintenanceapp.util.ApiClient;
import com.example.maintenanceapp.util.ScreenInsets;
import com.example.maintenanceapp.util.VehicleType;
import com.google.android.material.button.MaterialButton;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Edits an existing vehicle's fields and PUTs them to the backend. */
public class EditVehicleActivity extends AppCompatActivity {

    public static final String EXTRA_VEHICLE = "extra_vehicle";               // input
    public static final String EXTRA_RESULT_VEHICLE = "extra_result_vehicle"; // output on RESULT_OK

    private static final String UPDATE_URL = "http://92.5.55.85:27778/vehicles/update";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private OkHttpClient client;

    private Vehicle vehicle;
    private EditText edtMake, edtModel, edtYear, edtPlate, edtMileage, edtVin, edtColor;
    private Spinner spinnerFuelType, spinnerVehicleType;
    private MaterialButton btnSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_vehicle);

        client = ApiClient.get(this);

        ScreenInsets.apply(findViewById(R.id.evRoot));

        vehicle = (Vehicle) getIntent().getSerializableExtra(EXTRA_VEHICLE);
        if (vehicle == null) {
            finish();
            return;
        }

        ImageButton btnBack = findViewById(R.id.btnBack);
        edtMake = findViewById(R.id.edtMake);
        edtModel = findViewById(R.id.edtModel);
        edtYear = findViewById(R.id.edtYear);
        edtPlate = findViewById(R.id.edtPlate);
        edtMileage = findViewById(R.id.edtMileage);
        spinnerFuelType = findViewById(R.id.spinnerFuelType);
        spinnerVehicleType = findViewById(R.id.spinnerVehicleType);
        spinnerVehicleType.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, VehicleType.labels(this)));
        edtVin = findViewById(R.id.edtVin);
        edtColor = findViewById(R.id.edtColor);
        btnSave = findViewById(R.id.btnSave);

        btnBack.setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> save());

        prefill();
    }

    private void prefill() {
        // Ordinal, not a label match: the spinner is built straight from VehicleType.values(), and
        // an unknown stored value has already been normalised to CAR by fromApi.
        spinnerVehicleType.setSelection(VehicleType.of(vehicle).ordinal());
        edtMake.setText(orEmpty(vehicle.make));
        edtModel.setText(orEmpty(vehicle.model));
        edtYear.setText(vehicle.year > 0 ? String.valueOf(vehicle.year) : "");
        edtPlate.setText(orEmpty(vehicle.licensePlate));
        edtMileage.setText(vehicle.mileage > 0 ? String.valueOf(vehicle.mileage) : "");
        edtVin.setText(orEmpty(vehicle.vin));
        edtColor.setText(orEmpty(vehicle.color));

        // Select the current fuel type in the spinner, if it matches a catalog entry.
        String[] fuels = getResources().getStringArray(R.array.fuel_types);
        for (int i = 0; i < fuels.length; i++) {
            if (fuels[i].equalsIgnoreCase(orEmpty(vehicle.fuelType))) {
                spinnerFuelType.setSelection(i);
                break;
            }
        }
    }

    private void save() {
        Vehicle updated = new Vehicle(edtMake.getText().toString().trim(),
                edtModel.getText().toString().trim());
        updated.id = vehicle.id;
        updated.imageName = vehicle.imageName;        // not edited here
        updated.imageBase64 = vehicle.imageBase64;    // ditto — carry it so the photo survives a save
        // Document dates and country are owned by VehicleComplianceActivity, not this form. They
        // still have to be carried: /vehicles/update rewrites every column, so leaving them off
        // would blank whatever the user set on that screen the moment they corrected a typo here.
        updated.country = vehicle.country;
        updated.inspectionValidTo = vehicle.inspectionValidTo;
        updated.insuranceValidTo = vehicle.insuranceValidTo;
        updated.insuranceNextInstallment = vehicle.insuranceNextInstallment;
        updated.year = parseIntSafe(edtYear.getText().toString().trim());
        updated.licensePlate = edtPlate.getText().toString().trim();
        updated.mileage = parseIntSafe(edtMileage.getText().toString().trim());
        updated.vehicleType = VehicleType.at(spinnerVehicleType.getSelectedItemPosition()).apiValue;
        updated.fuelType = spinnerFuelType.getSelectedItem() == null
                ? "" : spinnerFuelType.getSelectedItem().toString();
        updated.vin = edtVin.getText().toString().trim();
        updated.color = edtColor.getText().toString().trim();

        JSONObject json;
        try {
            json = updated.toUpdateJson();
        } catch (JSONException e) {
            Toast.makeText(this, R.string.ev_error, Toast.LENGTH_SHORT).show();
            return;
        }

        btnSave.setEnabled(false);
        Request request = new Request.Builder()
                .url(UPDATE_URL)
                .post(RequestBody.create(json.toString(), JSON))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("EditVehicle", "POST /vehicles/update failed", e);
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    Toast.makeText(EditVehicleActivity.this, R.string.ev_error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) {
                boolean ok;
                try (Response r = response) {
                    ok = r.isSuccessful();
                }
                runOnUiThread(() -> {
                    if (ok) {
                        Toast.makeText(EditVehicleActivity.this, R.string.ev_saved, Toast.LENGTH_SHORT).show();
                        Intent data = new Intent();
                        data.putExtra(EXTRA_RESULT_VEHICLE, updated);
                        setResult(RESULT_OK, data);
                        finish();
                    } else {
                        btnSave.setEnabled(true);
                        Toast.makeText(EditVehicleActivity.this, R.string.ev_error, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
