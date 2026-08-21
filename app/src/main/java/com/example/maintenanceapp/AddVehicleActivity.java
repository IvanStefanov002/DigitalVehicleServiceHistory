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

    private static final String API_URL = "http://92.5.55.85:27778/vehicles/add";

    // Downscale/compress the chosen photo before sending: the image travels inline as base64 and is
    // stored that way, so full camera resolution would bloat every response that carries it back —
    // on a server that already truncates large payloads. Lossless WebP/PNG is used (not JPEG) so
    // images with transparent backgrounds keep their alpha.
    private static final int MAX_IMAGE_DIMEN = 1024;   // px, longest side

    private OkHttpClient client;

    private EditText edtMake, edtModel, edtYear, edtPlate, edtMileage, edtVin, edtColor;
    private MaterialAutoCompleteTextView ddFuelType, ddVehicleType;
    private ImageView imgVehiclePhoto;
    private Button btnPickPhoto, btnSave;
    private TextView txtPhotoHint;

    // Photo chosen by the user, ready to send (empty if none picked). Both are kept even while the
    // user is in default mode, so toggling back and forth doesn't make them pick the same photo
    // again — saveVehicle() decides whether to actually send it.
    private String imageBase64 = "";
    private Bitmap pickedPhoto;

    // false = let the app use its default image (what it did before this panel existed).
    private boolean uploadPhoto;

    // Modern photo picker — no runtime storage permission required.
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

        // Bars, cutout and keyboard — see ScreenInsets. The cutout part matters on this screen in
        // particular: with a camera notch the status-bar inset alone can be shorter than the
        // cutout, which left the back button underneath it.
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

        // The fuel picker is an exposed dropdown rather than a Spinner (see the layout). A Spinner
        // always had item 0 selected; an autocomplete starts empty, so seed it or a user who never
        // opens the dropdown would submit an empty fuelType.
        String[] fuelTypes = getResources().getStringArray(R.array.fuel_types);
        ddFuelType.setSimpleItems(fuelTypes);
        if (fuelTypes.length > 0) {
            ddFuelType.setText(fuelTypes[0], false);   // false = don't re-filter the list
        }

        // Same seeding rule as the fuel picker: the labels and their order come from VehicleType so
        // a position means the same thing here and on the edit screen, and the first entry (car) is
        // preselected because most additions are cars and an empty type must never be submitted.
        ddVehicleType.setSimpleItems(VehicleType.labels(this));
        ddVehicleType.setText(getString(VehicleType.DEFAULT.labelRes), false);
        // Changing the type re-renders the default-mode preview; in upload mode there is a real
        // photo on screen and applyPhotoMode leaves it alone.
        ddVehicleType.setOnItemClickListener((parent, view, pos, id) -> applyPhotoMode(uploadPhoto));

        btnPickPhoto.setOnClickListener(v -> pickMedia.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));

        // Photo mode. addOnButtonCheckedListener fires twice per switch — once unchecking the old
        // button, once checking the new one — so only the checked callback is acted on, otherwise
        // the panel would briefly render the state the user just left.
        MaterialButtonToggleGroup togglePhotoMode = findViewById(R.id.togglePhotoMode);
        togglePhotoMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                applyPhotoMode(checkedId == R.id.btnModeUpload);
            }
        });
        // Default mode matches what the app did before this panel existed: no photo picked ->
        // the server stores nothing and every screen falls back to the placeholder.
        togglePhotoMode.check(R.id.btnModeDefault);

        btnSave.setOnClickListener(v -> saveVehicle());
    }

    /**
     * Switches the photo panel between "use the default image" and "upload my own".
     *
     * <p>Only the UI changes here — the already-encoded photo (if any) is deliberately kept, so
     * flipping to default and back doesn't discard the user's pick. What gets sent is decided in
     * {@link #saveVehicle()} from {@code uploadPhoto}.
     */
    private void applyPhotoMode(boolean upload) {
        uploadPhoto = upload;
        btnPickPhoto.setVisibility(upload ? View.VISIBLE : View.GONE);
        if (!upload) {
            // The placeholder for the type currently picked, so the preview shows what the list row
            // will actually look like rather than always promising a car.
            imgVehiclePhoto.setImageResource(selectedVehicleType().placeholderRes);
            txtPhotoHint.setText(R.string.av_photo_hint_default);
        } else if (pickedPhoto != null) {
            imgVehiclePhoto.setImageBitmap(pickedPhoto);   // restore an earlier pick
            txtPhotoHint.setText(R.string.av_photo_hint_picked);
        } else {
            txtPhotoHint.setText(R.string.av_photo_hint_upload);
        }
    }

    private void handlePickedImage(Uri uri) {
        try {
            // Decoding, downsampling and the EXIF rotation all live in PickedImages, shared with the
            // document picker on AddMaintenanceActivity — the orientation fix is exactly the kind of
            // thing that would otherwise get made in one screen and forgotten in the other.
            Bitmap bitmap = PickedImages.decodeUpright(this, uri, MAX_IMAGE_DIMEN);
            if (bitmap == null) {
                Toast.makeText(this, "Could not read image", Toast.LENGTH_SHORT).show();
                return;
            }
            pickedPhoto = bitmap;
            imgVehiclePhoto.setImageBitmap(bitmap);
            txtPhotoHint.setText(R.string.av_photo_hint_picked);
            // Lossless, not JPEG: a vehicle photo may be a PNG with transparency, which JPEG would
            // flatten onto a solid background. (The document picker chooses JPEG for the opposite
            // reason — flat paper, where size matters more.)
            imageBase64 = Base64.encodeToString(
                    PickedImages.encodeLossless(bitmap), Base64.NO_WRAP);
        } catch (IOException | OutOfMemoryError e) {
            Log.w(TAG, "could not read picked image", e);
            Toast.makeText(this, "Could not read image", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * The type currently shown in the dropdown, matched back to the enum by label. Position isn't
     * usable here — an autocomplete filters as the user types, so a click index refers to the
     * filtered adapter rather than to {@code VehicleType.values()}.
     */
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
            Toast.makeText(this, "Make, model, year and plate are required", Toast.LENGTH_SHORT).show();
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
            // Empty in default mode (and when upload mode was chosen but no photo picked): the
            // server stores nothing and every screen falls back to the placeholder drawable.
            json.put("imageBase64", uploadPhoto ? imageBase64 : "");
        } catch (org.json.JSONException e) {
            throw new RuntimeException(e);
        }

        RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder().url(API_URL).post(body).build();

        btnSave.setEnabled(false);
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    Toast.makeText(AddVehicleActivity.this, "Server error", Toast.LENGTH_SHORT).show();
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
