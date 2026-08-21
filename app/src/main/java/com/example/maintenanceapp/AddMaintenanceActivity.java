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

/** Form for logging a service record: pick vehicle + type, enter mileage/cost/date/notes, POST it. */
public class AddMaintenanceActivity extends AppCompatActivity {

    public static final String EXTRA_VEHICLES = "extra_vehicles";

    // API endpoint used to retrieve( GET ) available maintenance types;
    private static final String TYPES_URL = "http://92.5.55.85:27778/maintenance/types";

    // API endpoint used to insert/add maintenance record to a vehicle;
    private static final String ADD_URL = "http://92.5.55.85:27778/vehicles/maintenance/add";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /**
     * Cap on the document photo's longest side. Larger than the 1024 px used for vehicle photos
     * because this one has to stay <em>legible</em> — the point of keeping a receipt is reading its
     * line items later, and 1024 px turns 8 pt print into mush.
     */
    private static final int MAX_DOC_DIMEN = 2048;

    /** JPEG quality for the document. 85 is visually clean on flat paper and roughly halves the size. */
    private static final int DOC_JPEG_QUALITY = 85;

    // Shown until GET /maintenance/types responds (or if it fails). The server catalog is the
    // source of truth; keep these names in sync with it.
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

    /**
     * Index into {@link #vehicles} of the chosen vehicle. Tracked by hand because an exposed-dropdown
     * MaterialAutoCompleteTextView has no getSelectedItemPosition() — it only knows its text, and two
     * vehicles could share a label.
     */
    private int selectedVehicleIndex = -1;
    private EditText edtMileage, edtCost, edtDate, edtNotes;
    private Button btnSave;

    private ImageView imgDocPreview;
    private MaterialButton btnAttachDoc, btnRemoveDoc;

    /**
     * The picked document, already downscaled, rotated upright and JPEG-encoded — i.e. exactly the
     * bytes that will be uploaded. Encoded at pick time rather than at save time so the cost is paid
     * while the user is still filling the form, and so a photo that can't be processed is reported
     * immediately instead of after they hit Запази.
     */
    private byte[] documentJpeg;

    /** Photo picker — no runtime storage permission needed, same as AddVehicleActivity's. */
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
        for (int i = 0; i < vehicles.size(); i++) {
            vehicleLabels[i] = vehicleLabel(vehicles.get(i));
        }
        ddVehicle.setSimpleItems(vehicleLabels);
        ddVehicle.setOnItemClickListener((parent, view, pos, id) -> selectVehicle(pos));
        if (!vehicles.isEmpty()) {
            selectVehicle(0);   // preselect so the form works without opening the dropdown
        }

        // Service types: fallback now, refreshed from the server when it responds.
        setTypeItems(FALLBACK_TYPES);
        fetchTypes();

        // Read-only field: tapping it opens the Material date picker.
        edtDate.setOnClickListener(v -> showDatePicker());

        btnSave.setOnClickListener(v -> save());

        if (vehicles.isEmpty()) {
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

    /** Selects a vehicle by index: reflects it in the dropdown text and prefills its mileage. */
    private void selectVehicle(int pos) {
        if (pos < 0 || pos >= vehicles.size()) {
            return;
        }
        selectedVehicleIndex = pos;
        ddVehicle.setText(vehicleLabel(vehicles.get(pos)), false);   // false = don't re-filter
        edtMileage.setText(String.valueOf(vehicles.get(pos).mileage));
    }

    /**
     * Fills the service-type dropdown, keeping the current choice when it still exists in the new
     * list — the server catalog can land after the user already picked from the fallback.
     */
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

    /** Material date picker; writes the ISO date the API expects (yyyy-MM-dd). */
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
        Request request = new Request.Builder().url(TYPES_URL).get().build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Maintenance", "GET /maintenance/types failed; using fallback", e);
                // keep the fallback list
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

    /**
     * Decodes, rotates and re-encodes the picked photo up front. Goes through
     * {@link PickedImages#decodeUpright} so the EXIF-orientation fix is shared with the vehicle-photo
     * picker — a portrait receipt stored sideways is exactly as useless as a sideways car photo, and
     * more so, because you can't read it.
     */
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

        // Optional: what the service cost. -1 means "not entered" — the field is then left out of
        // the body entirely, so the server can tell "free" (0) apart from "unknown".
        double cost = -1;
        // A Bulgarian keypad offers a comma as the decimal separator; Double.parseDouble only
        // accepts a dot, so normalise before parsing rather than rejecting "45,50" as invalid.
        String costStr = edtCost.getText().toString().trim().replace(',', '.');
        if (!costStr.isEmpty()) {
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
            Toast.makeText(this, R.string.am_error, Toast.LENGTH_SHORT).show();
            return;
        }

        btnSave.setEnabled(false);
        Request request = new Request.Builder()
                .url(ADD_URL)
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
                        // The id is only needed to attach a document. A server build that doesn't send
                        // one still saves the record fine — the photo is then the only thing lost, and
                        // finishSave says so rather than failing silently. So a parse failure here is
                        // logged and shrugged off, never turned into a failed save.
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

    /**
     * Uploads the attached photo after the record itself is safely saved.
     *
     * <p><b>The record is never rolled back if this fails.</b> The service entry is the data the user
     * cared about and it is already on the server; a failed photo upload is reported as exactly that,
     * so they don't have to retype a service because a few hundred KB didn't make it over a flaky
     * connection.
     */
    private void uploadDocument(String recordId) {
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

    /**
     * Closes the form. Returns {@code RESULT_OK} either way — the record exists, so the caller must
     * reload regardless; {@code documentFailed} only changes what the user is told.
     */
    private void finishSave(boolean documentFailed) {
        Toast.makeText(this,
                documentFailed ? R.string.am_saved_doc_failed : R.string.am_saved,
                documentFailed ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }
}
