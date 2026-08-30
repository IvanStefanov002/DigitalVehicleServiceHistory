package com.example.maintenanceapp;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.maintenanceapp.util.Api;
import com.example.maintenanceapp.util.ApiClient;
import com.example.maintenanceapp.util.PickedImages;
import com.example.maintenanceapp.util.ScreenInsets;
import com.example.maintenanceapp.util.VehicleType;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AddVehicleActivity extends AppCompatActivity {

    private static final String TAG = "AddVehicle";

    /** Downscale/compress the chosen photo before sending */
    private static final int MAX_IMAGE_DIMEN = 1024;   // px, longest side

    private OkHttpClient client;

    private EditText edtMake, edtModel, edtYear, edtPlate, edtMileage, edtVin, edtColor;
    private MaterialAutoCompleteTextView ddFuelType, ddVehicleType;
    private ImageView imgVehiclePhoto;
    private Button btnPickPhoto, btnSave;
    private TextView txtPhotoHint;
    private String imageBase64 = "";
    private Bitmap pickedPhoto;
    private boolean uploadPhoto;

    /** Modern photo picker — no runtime storage permission required. */
    private final ActivityResultLauncher<PickVisualMediaRequest> pickMedia =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    handlePickedImage(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_vehicle);

        client = ApiClient.get(this);
        ScreenInsets.apply(findViewById(R.id.avRoot));

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnPickPhoto = findViewById(R.id.btnPickPhoto);
        txtPhotoHint = findViewById(R.id.txtPhotoHint);
        imgVehiclePhoto = findViewById(R.id.imgVehiclePhoto);
        edtMake = findViewById(R.id.edtMake);
        edtModel = findViewById(R.id.edtModel);
        edtYear = findViewById(R.id.edtYear);
        edtPlate = findViewById(R.id.edtPlate);
        edtMileage = findViewById(R.id.edtMileage);
        ddFuelType = findViewById(R.id.ddFuelType);
        ddVehicleType = findViewById(R.id.ddVehicleType);
        edtVin = findViewById(R.id.edtVin);
        edtColor = findViewById(R.id.edtColor);
        btnSave = findViewById(R.id.btnSaveVehicle);

        btnBack.setOnClickListener(v -> finish());

        String[] fuelTypes = getResources().getStringArray(R.array.fuel_types);
        ddFuelType.setSimpleItems(fuelTypes);
        if (fuelTypes.length > 0) {
            ddFuelType.setText(fuelTypes[0], false);   // false = don't re-filter the list
        }

        ddVehicleType.setSimpleItems(VehicleType.labels(this));
        ddVehicleType.setText(getString(VehicleType.DEFAULT.labelRes), false);
        ddVehicleType.setOnItemClickListener((parent, view, pos, id) -> applyPhotoMode(uploadPhoto));

        btnPickPhoto.setOnClickListener(v -> pickMedia.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));

        MaterialButtonToggleGroup togglePhotoMode = findViewById(R.id.togglePhotoMode);
        togglePhotoMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                applyPhotoMode(checkedId == R.id.btnModeUpload);
            }
        });

        togglePhotoMode.check(R.id.btnModeDefault);
        btnSave.setOnClickListener(v -> saveVehicle());
    }

    /** Switches the photo panel between "use the default image" and "upload my own". */
    private void applyPhotoMode(boolean upload) {
        uploadPhoto = upload;
        btnPickPhoto.setVisibility(upload ? View.VISIBLE : View.GONE);
        if (!upload) {
            imgVehiclePhoto.setImageResource(selectedVehicleType().placeholderRes);
            txtPhotoHint.setText(R.string.av_photo_hint_default);
        } else if (pickedPhoto != null) {
            imgVehiclePhoto.setImageBitmap(pickedPhoto);
            txtPhotoHint.setText(R.string.av_photo_hint_picked);
        } else {
            txtPhotoHint.setText(R.string.av_photo_hint_upload);
        }
    }

    private void handlePickedImage(Uri uri) {
        try {
            Bitmap bitmap = PickedImages.decodeUpright(this, uri, MAX_IMAGE_DIMEN);
            if (bitmap == null) {
                Toast.makeText(this, "Could not read image", Toast.LENGTH_SHORT).show();
                return;
            }
            pickedPhoto = bitmap;
            imgVehiclePhoto.setImageBitmap(bitmap);
            txtPhotoHint.setText(R.string.av_photo_hint_picked);
            imageBase64 = Base64.encodeToString(
                    PickedImages.encodeLossless(bitmap), Base64.NO_WRAP);
        } catch (IOException | OutOfMemoryError e) {
            Log.w(TAG, "could not read picked image", e);
            Toast.makeText(this, "Could not read image", Toast.LENGTH_SHORT).show();
        }
    }

    private VehicleType selectedVehicleType() {
        String label = ddVehicleType.getText() == null ? "" : ddVehicleType.getText().toString().trim();
        for (VehicleType t : VehicleType.values()) {
            if (getString(t.labelRes).equals(label)) {
                return t;
            }
        }
        return VehicleType.DEFAULT;
    }

    private void saveVehicle() {
        String make = edtMake.getText().toString().trim();
        String model = edtModel.getText().toString().trim();
        String yearStr = edtYear.getText().toString().trim();
        String plate = edtPlate.getText().toString().trim();
        String mileageStr = edtMileage.getText().toString().trim();
        String fuelType = ddFuelType.getText().toString().trim();
        String vehicleType = selectedVehicleType().apiValue;
        String vin = edtVin.getText().toString().trim();
        String color = edtColor.getText().toString().trim();

        // Required fields
        if (make.isEmpty() || model.isEmpty() || yearStr.isEmpty() || plate.isEmpty()) {
            Toast.makeText(this, "Марка, модел, година и регистрационен номер са задължителни!", Toast.LENGTH_SHORT).show();
            return;
        }

        int year = Integer.parseInt(yearStr);           // safe: field is numeric-only
        int mileage = mileageStr.isEmpty() ? 0 : Integer.parseInt(mileageStr);

        // No username field — the backend attributes the vehicle to the user from the Bearer token.
        JSONObject json = new JSONObject();
        try {
            json.put("make", make);
            json.put("model", model);
            json.put("year", year);
            json.put("licensePlate", plate);
            json.put("mileage", mileage);
            json.put("fuelType", fuelType);
            json.put("vehicleType", vehicleType);
            json.put("vin", vin);
            json.put("color", color);
            json.put("imageBase64", uploadPhoto ? imageBase64 : "");
        } catch (org.json.JSONException e) {
            throw new RuntimeException(e);
        }

        RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder().url(Api.VEHICLE_ADD).post(body).build();

        btnSave.setEnabled(false);
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    Toast.makeText(AddVehicleActivity.this, "Системна грешка", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) {
                boolean ok = response.isSuccessful();
                response.close();
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    if (ok) {
                        Toast.makeText(AddVehicleActivity.this, "Добавено", Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);   // tells MainActivity to reload the list
                        finish();
                    } else {
                        Toast.makeText(AddVehicleActivity.this, "Неуспешно добавено", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }
}
