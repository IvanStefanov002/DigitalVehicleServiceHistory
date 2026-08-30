package com.example.maintenanceapp;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.maintenanceapp.model.Vehicle;
import com.example.maintenanceapp.util.Api;
import com.example.maintenanceapp.util.ApiClient;
import com.example.maintenanceapp.util.MaintenanceDocuments;
import com.example.maintenanceapp.util.PickedImages;
import com.example.maintenanceapp.util.ScreenInsets;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class AddMaintenanceActivity extends AppCompatActivity {

    public static final String EXTRA_VEHICLES = "extra_vehicles";


    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_DOC_DIMEN = 2048;
    private static final int DOC_JPEG_QUALITY = 85;

    /** in case fetch is not successful */
    private static final String[] FALLBACK_TYPES = {
            "Смяна на масло и филтър",
            "Въздушен и кабинен филтър",
            "Спирачни дискове",
            "Спирачни накладки",
            "Ангренажен ремък",
            "Гуми"
    };

    // client used for sending HTTP request to the server (attaches the Bearer token);
    private OkHttpClient client;

    private List<Vehicle> vehicles = new ArrayList<>();
    private MaterialAutoCompleteTextView ddVehicle, ddType;

    private int selectedVehicleIndex = -1;
    private EditText edtMileage, edtCost, edtDate, edtNotes;
    private Button btnSave;

    private ImageView imgDocPreview;
    private MaterialButton btnAttachDoc, btnRemoveDoc;

    private byte[] documentJpeg;

    /** Photo picker */
    private final ActivityResultLauncher<PickVisualMediaRequest> pickDocument =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    handlePickedDocument(uri);
                }
            });

    @Override
    @SuppressWarnings("unchecked")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate( savedInstanceState );
        setContentView( R.layout.activity_add_maintenance );

        client = ApiClient.get(this);

        ScreenInsets.apply( findViewById(R.id.amRoot) );

        Object extra = getIntent().getSerializableExtra( EXTRA_VEHICLES );
        if ( extra instanceof List ) {
            vehicles = (List<Vehicle>) extra;
        }

        ImageButton btnBack = findViewById(R.id.btnBack);
        ddVehicle = findViewById(R.id.ddVehicle);
        ddType = findViewById(R.id.ddType);
        edtMileage = findViewById(R.id.edtMileage);
        edtCost = findViewById(R.id.edtCost);
        edtDate = findViewById(R.id.edtDate);
        edtNotes = findViewById(R.id.edtNotes);
        imgDocPreview = findViewById(R.id.imgDocPreview);
        btnAttachDoc = findViewById(R.id.btnAttachDoc);
        btnRemoveDoc = findViewById(R.id.btnRemoveDoc);
        btnAttachDoc.setOnClickListener(v -> pickDocument.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build()));
        btnRemoveDoc.setOnClickListener(v -> clearDocument());

        btnSave = findViewById(R.id.btnSave);

        btnBack.setOnClickListener(v -> finish());

        // Vehicle picker
        String[] vehicleLabels = new String[vehicles.size()];
        for ( int i = 0; i < vehicles.size(); i++ ) {
            vehicleLabels[i] = vehicleLabel(vehicles.get(i));
        }
        ddVehicle.setSimpleItems(vehicleLabels);
        ddVehicle.setOnItemClickListener((parent, view, pos, id) -> selectVehicle(pos));
        if ( !vehicles.isEmpty() ) {
            selectVehicle(0);   // preselect so the form works without opening the dropdown
        }

        // fallback now, refreshed from the server when it responds.
        setTypeItems( FALLBACK_TYPES );
        fetchTypes();

        // tapping it opens the Material date picker.
        edtDate.setOnClickListener(v -> showDatePicker());

        btnSave.setOnClickListener(v -> save());

        if ( vehicles.isEmpty() ) {
            btnSave.setEnabled(false);
            Toast.makeText(this, R.string.maint_no_vehicles, Toast.LENGTH_LONG).show();
        }
    }

    private String vehicleLabel(Vehicle v) {
        String name = ((v.make == null ? "" : v.make) + " " + (v.model == null ? "" : v.model)).trim();
        if (v.licensePlate != null && !v.licensePlate.isEmpty()) {
            name = name + " — " + v.licensePlate;
        }
        return name.isEmpty() ? "—" : name;
    }

    /** Selects a vehicle by index */
    private void selectVehicle( int pos ) {
        if (pos < 0 || pos >= vehicles.size()) {
            return;
        }
        selectedVehicleIndex = pos;
        ddVehicle.setText(vehicleLabel(vehicles.get(pos)), false);   // false = don't re-filter
        edtMileage.setText(String.valueOf(vehicles.get(pos).mileage));
    }

    private void setTypeItems(String[] names) {
        String current = ddType.getText() == null ? "" : ddType.getText().toString();
        ddType.setSimpleItems(names);

        String keep = names.length > 0 ? names[0] : "";
        for (String n : names) {
            if (n.equals(current)) {
                keep = current;
                break;
            }
        }
        ddType.setText(keep, false);
    }

    private void showDatePicker() {
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.am_pick_date)
                .build();
        picker.addOnPositiveButtonClickListener(selection -> {
            // The picker returns UTC midnight — format in UTC or the date can shift by a day.
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            edtDate.setText(fmt.format(new Date(selection)));
        });
        picker.show(getSupportFragmentManager(), "am_date_picker");
    }

    private void fetchTypes() {
        Request request = new Request.Builder().url(Api.MAINTENANCE_TYPES).get().build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Maintenance", "GET /maintenance/types failed; using fallback", e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                List<String> names = new ArrayList<>();
                try (Response r = response) {
                    if (r.isSuccessful() && r.body() != null) {
                        JSONArray arr = new JSONObject(r.body().string()).optJSONArray("types");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject o = arr.optJSONObject(i);
                                if (o == null) continue;
                                String name = o.optString("name", "");
                                if (!name.isEmpty()) names.add(name);
                            }
                        }
                    }
                } catch (IOException | JSONException e) {
                    Log.e("Maintenance", "types parse failed; using fallback", e);
                }
                if (!names.isEmpty()) {
                    runOnUiThread(() -> setTypeItems(names.toArray(new String[0])));
                }
            }
        });
    }

    private void handlePickedDocument(Uri uri) {
        Bitmap bitmap;
        try {
            bitmap = PickedImages.decodeUpright(this, uri, MAX_DOC_DIMEN);
        } catch (IOException | OutOfMemoryError e) {
            Log.w("Maintenance", "could not read picked document", e);
            Toast.makeText(this, R.string.am_doc_read_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (bitmap == null) {
            Toast.makeText(this, R.string.am_doc_read_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        documentJpeg = PickedImages.encodeJpeg(bitmap, DOC_JPEG_QUALITY);
        imgDocPreview.setImageBitmap(bitmap);
        imgDocPreview.setVisibility(android.view.View.VISIBLE);
        btnRemoveDoc.setVisibility(android.view.View.VISIBLE);
        btnAttachDoc.setText(R.string.am_doc_replace);
    }

    private void clearDocument() {
        documentJpeg = null;
        imgDocPreview.setImageDrawable(null);
        imgDocPreview.setVisibility(android.view.View.GONE);
        btnRemoveDoc.setVisibility(android.view.View.GONE);
        btnAttachDoc.setText(R.string.am_doc_attach);
    }

    private void save() {
        if (vehicles.isEmpty()) return;

        if (selectedVehicleIndex < 0 || selectedVehicleIndex >= vehicles.size()) return;
        Vehicle vehicle = vehicles.get(selectedVehicleIndex);

        String mileageStr = edtMileage.getText().toString().trim();
        int mileage;
        try {
            mileage = Integer.parseInt(mileageStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.am_invalid_mileage, Toast.LENGTH_SHORT).show();
            return;
        }

        double cost = -1;
        String costStr = edtCost.getText().toString().trim().replace(',', '.');
        if ( !costStr.isEmpty() ) {
            try {
                cost = Double.parseDouble(costStr);
            } catch (NumberFormatException e) {
                Toast.makeText(this, R.string.am_invalid_cost, Toast.LENGTH_SHORT).show();
                return;
            }
            // Infinity/NaN are parseable ("1e999") and would make JSONObject.put throw below.
            if (cost < 0 || Double.isNaN(cost) || Double.isInfinite(cost)) {
                Toast.makeText(this, R.string.am_invalid_cost, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        String type = ddType.getText() == null ? "" : ddType.getText().toString().trim();
        String date = edtDate.getText().toString().trim();
        String notes = edtNotes.getText().toString().trim();

        JSONObject json = new JSONObject();
        try {
            json.put("vehicleId", vehicle.id);
            json.put("type", type);
            json.put("mileage", mileage);
            if (cost >= 0) json.put("cost", cost);
            if (!date.isEmpty()) json.put("date", date);
            if (!notes.isEmpty()) json.put("notes", notes);
        } catch (JSONException e) {
            Toast.makeText( this, R.string.am_error, Toast.LENGTH_SHORT ).show();
            return;
        }

        btnSave.setEnabled(false);
        Request request = new Request.Builder()
                .url(Api.MAINTENANCE_ADD)
                .post(RequestBody.create(json.toString(), JSON))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Maintenance", "POST /vehicles/maintenance/add failed", e);
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    Toast.makeText(AddMaintenanceActivity.this, R.string.am_error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) {
                boolean ok;
                String recordId = "";
                try (Response r = response) {
                    ok = r.isSuccessful();
                    ResponseBody body = r.body();
                    if (ok && body != null) {
                        // The id is only needed to attach a document.
                        try {
                            recordId = new JSONObject(body.string()).optString("id", "");
                        } catch (JSONException | IOException e) {
                            Log.w("Maintenance", "add response carried no usable record id", e);
                        }
                    }
                }

                final boolean success = ok;
                final String id = recordId;
                runOnUiThread(() -> {
                    if (!success) {
                        btnSave.setEnabled(true);
                        Toast.makeText(AddMaintenanceActivity.this, R.string.am_error, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (documentJpeg == null || id.isEmpty()) {
                        finishSave(documentJpeg != null);
                        return;
                    }
                    uploadDocument(id);
                });
            }
        });
    }

    /** Uploads the attached photo after the record itself is safely saved. */
    private void uploadDocument( String recordId ) {
        MaintenanceDocuments.upload(this, recordId, documentJpeg, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w("Maintenance", "document upload failed", e);
                runOnUiThread(() -> finishSave(true));
            }

            @Override
            public void onResponse(Call call, Response response) {
                boolean ok;
                try (Response r = response) {
                    ok = r.isSuccessful();
                    if (!ok) {
                        Log.w("Maintenance", "document upload -> HTTP " + r.code());
                    }
                }
                final boolean failed = !ok;
                runOnUiThread(() -> finishSave(failed));
            }
        });
    }

    private void finishSave(boolean documentFailed) {
        Toast.makeText(this,
                documentFailed ? R.string.am_saved_doc_failed : R.string.am_saved,
                documentFailed ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }
}
