package com.example.maintenanceapp;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.maintenanceapp.model.Vehicle;
import com.example.maintenanceapp.util.Api;
import com.example.maintenanceapp.util.ApiClient;
import com.example.maintenanceapp.util.PickedImages;
import com.example.maintenanceapp.util.ScreenInsets;
import com.example.maintenanceapp.util.VehicleImages;
import com.example.maintenanceapp.util.VehicleType;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

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

    public static final String EXTRA_VEHICLE = "extra_vehicle";
    public static final String EXTRA_RESULT_VEHICLE = "extra_result_vehicle";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_IMAGE_DIMEN = 1024;

    private OkHttpClient client;

    private Vehicle vehicle;
    private EditText edtMake, edtModel, edtYear, edtPlate, edtMileage, edtVin, edtColor;
    private Spinner spinnerFuelType, spinnerVehicleType;
    private MaterialButton btnSave, btnPickPhoto;
    private MaterialButtonToggleGroup togglePhotoMode;
    private ImageView imgVehiclePhoto;
    private TextView txtPhotoHint;

    private enum PhotoAction { NONE, REPLACE, CLEAR }

    private PhotoAction photoAction = PhotoAction.NONE;
    private Bitmap pickedPhoto;
    private String pickedBase64 = "";
    private boolean uploadPhoto;
    private boolean hasExistingPhoto;
    private boolean bindingPhotoMode;

    private final ActivityResultLauncher<PickVisualMediaRequest> pickMedia =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    handlePickedImage(uri);
                }
            });

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
        togglePhotoMode = findViewById(R.id.togglePhotoMode);
        imgVehiclePhoto = findViewById(R.id.imgVehiclePhoto);
        btnPickPhoto = findViewById(R.id.btnPickPhoto);
        txtPhotoHint = findViewById(R.id.txtPhotoHint);

        btnBack.setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> save());

        prefill();
        setUpPhotoPanel();
    }

    private void setUpPhotoPanel() {
        hasExistingPhoto = VehicleImages.cached(vehicle.id) != null
                || (vehicle.imageBase64 != null && !vehicle.imageBase64.isEmpty());

        btnPickPhoto.setOnClickListener(v -> pickMedia.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));

        togglePhotoMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            boolean upload = checkedId == R.id.btnModeUpload;
            if (!bindingPhotoMode) {
                if (!upload) {
                    photoAction = PhotoAction.CLEAR;
                } else {
                    photoAction = pickedPhoto != null ? PhotoAction.REPLACE : PhotoAction.NONE;
                }
            }
            applyPhotoMode(upload);
        });

        spinnerVehicleType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                applyPhotoMode(uploadPhoto);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        bindingPhotoMode = true;
        togglePhotoMode.check(hasExistingPhoto ? R.id.btnModeUpload : R.id.btnModeDefault);
        bindingPhotoMode = false;
    }

    private void applyPhotoMode(boolean upload) {
        uploadPhoto = upload;
        btnPickPhoto.setVisibility(upload ? View.VISIBLE : View.GONE);
        if (!upload) {
            imgVehiclePhoto.setImageResource(selectedVehicleType().placeholderRes);
            txtPhotoHint.setText(hasExistingPhoto
                    ? R.string.ev_photo_hint_default : R.string.av_photo_hint_default);
            return;
        }
        if (pickedPhoto != null) {
            imgVehiclePhoto.setImageBitmap(pickedPhoto);
            txtPhotoHint.setText(R.string.av_photo_hint_picked);
            return;
        }
        VehicleImages.apply(this, imgVehiclePhoto, vehicle.imageBase64, vehicle.imageName,
                vehicle.id, selectedVehicleType());
        txtPhotoHint.setText(hasExistingPhoto
                ? R.string.ev_photo_hint_current : R.string.av_photo_hint_upload);
    }

    private void handlePickedImage(Uri uri) {
        try {
            Bitmap bitmap = PickedImages.decodeUpright(this, uri, MAX_IMAGE_DIMEN);
            if (bitmap == null) {
                Toast.makeText(this, R.string.ev_photo_error, Toast.LENGTH_SHORT).show();
                return;
            }
            pickedPhoto = bitmap;
            pickedBase64 = Base64.encodeToString(
                    PickedImages.encodeLossless(bitmap), Base64.NO_WRAP);
            photoAction = PhotoAction.REPLACE;
            applyPhotoMode(true);
        } catch (IOException | OutOfMemoryError e) {
            Log.w("EditVehicle", "could not read picked image", e);
            Toast.makeText(this, R.string.ev_photo_error, Toast.LENGTH_SHORT).show();
        }
    }

    private VehicleType selectedVehicleType() {
        return VehicleType.at(spinnerVehicleType.getSelectedItemPosition());
    }

    private void prefill() {
        spinnerVehicleType.setSelection(VehicleType.of(vehicle).ordinal());
        edtMake.setText(orEmpty(vehicle.make));
        edtModel.setText(orEmpty(vehicle.model));
        edtYear.setText(vehicle.year > 0 ? String.valueOf(vehicle.year) : "");
        edtPlate.setText(orEmpty(vehicle.licensePlate));
        edtMileage.setText(vehicle.mileage > 0 ? String.valueOf(vehicle.mileage) : "");
        edtVin.setText(orEmpty(vehicle.vin));
        edtColor.setText(orEmpty(vehicle.color));

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
        updated.imageName = vehicle.imageName;
        updated.imageBase64 = "";
        updated.country = vehicle.country;
        updated.inspectionValidTo = vehicle.inspectionValidTo;
        updated.insuranceValidTo = vehicle.insuranceValidTo;
        updated.insuranceNextInstallment = vehicle.insuranceNextInstallment;
        updated.year = parseIntSafe(edtYear.getText().toString().trim());
        updated.licensePlate = edtPlate.getText().toString().trim();
        updated.mileage = parseIntSafe(edtMileage.getText().toString().trim());
        updated.vehicleType = VehicleType.at(spinnerVehicleType.getSelectedItemPosition()).apiValue;
        updated.fuelType = spinnerFuelType.getSelectedItem() == null ? "" : spinnerFuelType.getSelectedItem().toString();
        updated.vin = edtVin.getText().toString().trim();
        updated.color = edtColor.getText().toString().trim();

        JSONObject json;
        try {
            json = updated.toUpdateJson();
            if (photoAction == PhotoAction.REPLACE) {
                json.put("imageBase64", pickedBase64);
                updated.imageBase64 = "";
                updated.imageName = "";
            } else if (photoAction == PhotoAction.CLEAR) {
                json.put("imageBase64", "");
                updated.imageBase64 = "";
                updated.imageName = "";
            }
        } catch (JSONException e) {
            Toast.makeText(this, R.string.ev_error, Toast.LENGTH_SHORT).show();
            return;
        }

        btnSave.setEnabled(false);
        Request request = new Request.Builder()
                .url(Api.VEHICLE_UPDATE)
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
                        if (photoAction != PhotoAction.NONE) {
                            VehicleImages.evict(vehicle.id);
                        }
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
