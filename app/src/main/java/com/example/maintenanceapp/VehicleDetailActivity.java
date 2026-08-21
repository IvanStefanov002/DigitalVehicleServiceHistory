package com.example.maintenanceapp;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.maintenanceapp.model.MaintenanceItem;
import com.example.maintenanceapp.model.Vehicle;
import com.example.maintenanceapp.util.ApiClient;
import com.example.maintenanceapp.util.ComplianceStatus;
import com.example.maintenanceapp.util.MaintenanceStatus;
import com.example.maintenanceapp.util.ScreenInsets;
import com.example.maintenanceapp.util.ServiceHistoryPdf;
import com.example.maintenanceapp.util.SwipeRefresh;
import com.example.maintenanceapp.util.VehicleImages;
import com.example.maintenanceapp.util.VehicleType;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class VehicleDetailActivity extends AppCompatActivity {

    public static final String EXTRA_VEHICLE = "extra_vehicle";

    // Returns one vehicle's photo as {"imageBase64":"<data>"} and/or {"imageName":"<name>"}, the
    // latter naming a bundled res/drawable. See fetchPhoto.
    private static final String IMAGE_URL = "http://92.5.55.85:27778/vehicles/image";
    private static final int IMAGE_MAX_ATTEMPTS = 3;

    private static final String MAINTENANCE_URL = "http://92.5.55.85:27778/vehicles/maintenance";
    // Full record list (no latest-per-type collapsing) — only the PDF export reads this.
    private static final String HISTORY_URL = "http://92.5.55.85:27778/vehicles/maintenance/history";
    private static final int HISTORY_MAX_ATTEMPTS = 3;
    private static final int MAINTENANCE_MAX_ATTEMPTS = 3;

    // Body {"id":"<id>"}; deletes the vehicle and its maintenance records. NOT retried: unlike the
    // GETs, this mutates, and a retry after a response that was actually delivered-but-truncated
    // would fire a second delete.
    private static final String DELETE_URL = "http://92.5.55.85:27778/vehicles/delete";

    // Body {"id":"<recordId>"}; deletes ONE maintenance record. Also not retried (see above).
    private static final String RECORD_DELETE_URL =
            "http://92.5.55.85:27778/vehicles/maintenance/delete";

    // TODO(temporary): preview the maintenance UI with local sample data until the C++
    // /vehicles/maintenance endpoint exists. Set to false (or delete buildSampleMaintenance
    // + this flag) to use the real server response.
    private static final boolean USE_SAMPLE_MAINTENANCE = false;

    private OkHttpClient client;

    private Vehicle vehicle;

    /** Guards against a double-tap firing two deletes while the first request is still open. */
    private boolean deleteInFlight;

    /** Same guard for single-record deletes (the cards are re-inflated on every refetch). */
    private boolean recordDeleteInFlight;

    /** Stops a double-tap on the export button rendering and sharing the same PDF twice. */
    private boolean exportInFlight;

    /** Which action the user picked before the export started: save to a file, or share. */
    private boolean saveAfterExport;

    /** The rendered PDF, held while the "create document" picker is open. */
    private File pendingExportFile;

    /**
     * Lets the user choose where the PDF is written. SAF needs no storage permission on any API
     * level, so nothing has to be requested and there's no pre-API-29 MediaStore fallback.
     */
    private final ActivityResultLauncher<String> createDocument =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/pdf"), uri -> {
                if (uri != null) {
                    saveTo(uri);
                } else {
                    pendingExportFile = null;   // user backed out of the picker
                }
            });

    /** Root pull-to-refresh host; also the view that receives the window-inset padding. */
    private SwipeRefreshLayout swipe;

    // Opens the edit form; on success updates the shown data and flags the list for a refresh.
    private final ActivityResultLauncher<Intent> editVehicleLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Vehicle updated = (Vehicle) result.getData()
                            .getSerializableExtra(EditVehicleActivity.EXTRA_RESULT_VEHICLE);
                    if (updated != null) {
                        vehicle = updated;
                        bindVehicleInfo();
                        setResult(RESULT_OK);   // tell MainActivity to reload the list on back
                    }
                }
            });

    /**
     * Opens the per-vehicle documents screen. It edits the same vehicle row this screen shows (the
     * ГТП/ГО dates go through /vehicles/update), so its result is handled exactly like the edit
     * form's: re-bind from the returned vehicle and flag MainActivity for a reload.
     */
    private final ActivityResultLauncher<Intent> complianceLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Vehicle updated = (Vehicle) result.getData()
                            .getSerializableExtra(VehicleComplianceActivity.EXTRA_RESULT_VEHICLE);
                    if (updated != null) {
                        vehicle = updated;
                        bindVehicleInfo();
                        setResult(RESULT_OK);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vehicle_detail);

        client = ApiClient.get(this);

        // App targets SDK 36 -> edge-to-edge by default, so the header (back button) would otherwise
        // be drawn under the status bar and left untappable.
        ScreenInsets.apply(findViewById(R.id.detailRoot));

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        vehicle = (Vehicle) getIntent().getSerializableExtra(EXTRA_VEHICLE);
        if (vehicle == null) {
            finish();
            return;
        }

        ImageView imgVehicle = findViewById(R.id.imgVehicle);

        bindVehicleInfo();

        findViewById(R.id.btnEdit).setOnClickListener(view -> {
            Intent intent = new Intent(this, EditVehicleActivity.class);
            intent.putExtra(EditVehicleActivity.EXTRA_VEHICLE, vehicle);
            editVehicleLauncher.launch(intent);
        });

        findViewById(R.id.cardDocuments).setOnClickListener(view -> {
            Intent intent = new Intent(this, VehicleComplianceActivity.class);
            intent.putExtra(VehicleComplianceActivity.EXTRA_VEHICLE, vehicle);
            complianceLauncher.launch(intent);
        });

        findViewById(R.id.btnDelete).setOnClickListener(view -> confirmDelete());
        findViewById(R.id.btnExport).setOnClickListener(view -> exportHistory());

        swipe = findViewById(R.id.detailRoot);
        SwipeRefresh.theme(swipe);
        swipe.setOnRefreshListener(this::refreshFromServer);

        // Show whatever the list row already had, then ask the server for this car's photo
        // (GET /vehicles/image?id=) and swap in the full-size one.
        VehicleImages.apply(this, imgVehicle, vehicle.imageBase64, vehicle.imageName, vehicle.id,
                VehicleType.of(vehicle));
        if (vehicle.id != null && !vehicle.id.isEmpty()) {
            fetchPhoto(vehicle.id, imgVehicle, 1);
        }

        // Load the maintenance schedule; status is computed against the current mileage.
        findViewById(R.id.maintenanceProgress).setVisibility(View.VISIBLE);
        findViewById(R.id.historyEmpty).setVisibility(View.GONE);
        if (USE_SAMPLE_MAINTENANCE) {
            renderMaintenance(buildSampleMaintenance(vehicle.mileage), vehicle.mileage);
        } else if (vehicle.id != null && !vehicle.id.isEmpty()) {
            fetchMaintenance(vehicle.id, vehicle.mileage, 1);
        } else {
            renderMaintenance(new ArrayList<>(), vehicle.mileage);
        }
    }

    /**
     * Pull-to-refresh: re-runs this vehicle's two server reads (photo name + maintenance schedule).
     * The vehicle's own fields came from the list in MainActivity, so they are not refetched here —
     * an edit already round-trips them through EditVehicleActivity.
     */
    private void refreshFromServer() {
        if (vehicle.id == null || vehicle.id.isEmpty()) {
            swipe.setRefreshing(false);
            return;
        }
        fetchPhoto(vehicle.id, findViewById(R.id.imgVehicle), 1);
        fetchMaintenance(vehicle.id, vehicle.mileage, 1);
    }

    /** Confirms deletion of a single maintenance record, naming the item and its mileage. */
    private void confirmDeleteRecord(MaintenanceItem item) {
        if (recordDeleteInFlight) {
            return;
        }
        String mileage = item.lastChangeMileage > 0 ? formatKm(item.lastChangeMileage) : "-";
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.record_delete_title)
                .setMessage(getString(R.string.record_delete_message, orDash(item.name), mileage))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> deleteRecord(item))
                .show();
    }

    /**
     * POSTs {@code {"id":...}} to /vehicles/maintenance/delete, then refetches the schedule.
     *
     * <p>The refetch matters: the server returns the <em>latest</em> record per service type, so
     * deleting one doesn't necessarily remove the card — it falls back to the previous record for
     * that type, with different mileage and possibly a different status. Only a refetch shows that
     * correctly. {@code setResult(RESULT_OK)} is also set because the worst-case status can change,
     * which the Автопарк badges and reminder banner are derived from.
     */
    private void deleteRecord(MaintenanceItem item) {
        String json;
        try {
            json = new JSONObject().put("id", item.id).toString();
        } catch (JSONException e) {
            Toast.makeText(this, R.string.record_delete_error, Toast.LENGTH_SHORT).show();
            return;
        }

        recordDeleteInFlight = true;
        Request request = new Request.Builder()
                .url(RECORD_DELETE_URL)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("VehicleDetail", "POST /vehicles/maintenance/delete failed", e);
                runOnUiThread(() -> {
                    recordDeleteInFlight = false;
                    Toast.makeText(VehicleDetailActivity.this,
                            R.string.record_delete_error, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) {
                boolean ok;
                try (Response r = response) {
                    ok = r.isSuccessful();
                }
                runOnUiThread(() -> {
                    recordDeleteInFlight = false;
                    if (!ok) {
                        Toast.makeText(VehicleDetailActivity.this,
                                R.string.record_delete_error, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(VehicleDetailActivity.this,
                            R.string.record_deleted, Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);   // fleet badges/banner may change
                    findViewById(R.id.maintenanceProgress).setVisibility(View.VISIBLE);
                    fetchMaintenance(vehicle.id, vehicle.mileage, 1);
                });
            }
        });
    }

    // ---- PDF export ----------------------------------------------------------

    /**
     * Asks what to do with the export, then fetches and renders it. The choice is made <em>first</em>
     * so the user isn't left waiting on a download and then asked a question.
     */
    private void exportHistory() {
        if (vehicle.id == null || vehicle.id.isEmpty()) {
            Toast.makeText(this, R.string.pdf_error, Toast.LENGTH_SHORT).show();
            return;
        }
        if (exportInFlight) {
            return;   // a second tap would render the same file twice
        }
        // Three ways to hand the history over: a file the user keeps, a file they send, or a live
        // link that stays current. The first two share the fetch-and-render path; the third is a
        // different feature entirely and goes to its own screen.
        String[] actions = {
                getString(R.string.pdf_action_save),
                getString(R.string.pdf_action_share),
                getString(R.string.pdf_action_link),
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pdf_share_title)
                .setItems(actions, (dialog, which) -> {
                    if (which == 2) {
                        startActivity(new Intent(this, ShareHistoryActivity.class)
                                .putExtra(ShareHistoryActivity.EXTRA_VEHICLE, vehicle));
                    } else {
                        startExport(which == 0);
                    }
                })
                .show();
    }

    /**
     * Fetches the <em>full</em> history and renders the PDF.
     *
     * <p>Uses {@code /vehicles/maintenance/history}, not the {@code /vehicles/maintenance} the rest
     * of this screen reads: that one returns the latest record per type, which would make a
     * "history" document showing one line per service type — not a history at all.
     *
     * @param save true to write the file where the user chooses, false to open a share sheet.
     */
    private void startExport(boolean save) {
        exportInFlight = true;
        saveAfterExport = save;
        findViewById(R.id.btnExport).setEnabled(false);
        Toast.makeText(this, R.string.pdf_exporting, Toast.LENGTH_SHORT).show();
        fetchHistory(vehicle.id, 1);
    }

    private void fetchHistory(String id, int attempt) {
        HttpUrl url = HttpUrl.parse(HISTORY_URL).newBuilder()
                .addQueryParameter("id", id)
                .build();
        Request request = new Request.Builder().url(url).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Export", "GET /vehicles/maintenance/history failed (attempt " + attempt + ")", e);
                retryOrFail(id, attempt);
            }

            @Override
            public void onResponse(Call call, Response response) {
                List<MaintenanceItem> items = null;
                try (Response r = response) {
                    if (!r.isSuccessful()) {
                        Log.e("Export", "history -> HTTP " + r.code());
                        runOnUiThread(() -> finishExport(null));
                        return;   // not transient; retrying won't help
                    }
                    if (r.body() != null) {
                        items = MaintenanceItem.listFromJson(r.body().string());
                    }
                } catch (IOException | JSONException e) {
                    // Same rule as the photo fetch: a body cut mid-payload surfaces as a parse
                    // failure, not an IOException, so both are retried.
                    Log.e("Export", "history read/parse failed (attempt " + attempt + ")", e);
                }

                if (items == null) {
                    retryOrFail(id, attempt);
                    return;
                }
                // Render off the main thread — this callback is already on OkHttp's.
                File file = null;
                try {
                    file = new ServiceHistoryPdf(getApplicationContext()).write(vehicle, items);
                } catch (IOException | RuntimeException e) {
                    Log.e("Export", "PDF render failed", e);
                }
                final File rendered = file;
                runOnUiThread(() -> finishExport(rendered));
            }
        });
    }

    private void retryOrFail(String id, int attempt) {
        if (attempt < HISTORY_MAX_ATTEMPTS) {
            fetchHistory(id, attempt + 1);
        } else {
            runOnUiThread(() -> finishExport(null));
        }
    }

    /** Re-enables the button and, when the render succeeded, saves or shares as chosen. */
    private void finishExport(File file) {
        exportInFlight = false;
        findViewById(R.id.btnExport).setEnabled(true);
        if (file == null) {
            Toast.makeText(this, R.string.pdf_error, Toast.LENGTH_LONG).show();
            return;
        }
        if (saveAfterExport) {
            // Storage Access Framework: the user picks the folder (Downloads or anywhere else) and
            // can rename the file. Chosen over writing to Downloads directly because it needs *no*
            // storage permission on any API level — MediaStore is only permission-free from API 29,
            // and minSdk here is 24, which would have meant a WRITE_EXTERNAL_STORAGE path as well.
            pendingExportFile = file;
            createDocument.launch(file.getName());
        } else {
            share(file);
        }
    }

    /**
     * Copies the rendered PDF into the document the user picked. The file is small (tens of KB), so
     * this stays on the main thread rather than dragging in an executor for a single copy.
     */
    private void saveTo(Uri target) {
        File source = pendingExportFile;
        pendingExportFile = null;
        if (source == null) {
            // The Activity was recreated while the picker was open, so the reference is gone.
            Toast.makeText(this, R.string.pdf_save_error, Toast.LENGTH_LONG).show();
            return;
        }
        try (InputStream in = new FileInputStream(source);
             OutputStream out = getContentResolver().openOutputStream(target)) {
            if (out == null) {
                throw new IOException("openOutputStream returned null for " + target);
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Log.e("Export", "could not write the picked document", e);
            Toast.makeText(this, R.string.pdf_save_error, Toast.LENGTH_LONG).show();
            return;
        }
        // Offer to open it — a toast alone leaves the user hunting for where it went.
        Snackbar.make(findViewById(R.id.detailRoot), R.string.pdf_saved, Snackbar.LENGTH_LONG)
                .setAction(R.string.pdf_open, v -> openPdf(target))
                .show();
    }

    private void openPdf(Uri uri) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.pdf_no_app, Toast.LENGTH_LONG).show();
        }
    }

    private void share(File file) {
        // A file:// URI would throw FileUriExposedException from API 24 on; FileProvider hands out
        // a content:// URI the receiving app is granted read access to.
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT,
                        getString(R.string.pdf_share_title) + " — " + join(vehicle.make, vehicle.model))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.pdf_share_title)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.pdf_no_app, Toast.LENGTH_LONG).show();
        }
    }

    /** Asks for confirmation before deleting — the server drops the maintenance history too. */
    private void confirmDelete() {
        if (deleteInFlight) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(getString(R.string.delete_confirm_message, join(vehicle.make, vehicle.model)))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> deleteVehicle())
                .show();
    }

    /**
     * POSTs {@code {"id":...}} to /vehicles/delete. On success finishes with {@code RESULT_OK}, which
     * is what makes {@code MainActivity.vehicleDetailLauncher} reload the list — the deleted row and
     * its status badge then disappear together.
     */
    private void deleteVehicle() {
        if (vehicle.id == null || vehicle.id.isEmpty()) {
            Toast.makeText(this, R.string.delete_error, Toast.LENGTH_SHORT).show();
            return;
        }

        deleteInFlight = true;   // a second tap must not fire a second delete
        findViewById(R.id.btnDelete).setEnabled(false);

        String json;
        try {
            json = new JSONObject().put("id", vehicle.id).toString();
        } catch (JSONException e) {
            deleteInFlight = false;
            findViewById(R.id.btnDelete).setEnabled(true);
            Toast.makeText(this, R.string.delete_error, Toast.LENGTH_SHORT).show();
            return;
        }

        Request request = new Request.Builder()
                .url(DELETE_URL)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("VehicleDetail", "POST /vehicles/delete failed", e);
                runOnUiThread(() -> {
                    deleteInFlight = false;
                    findViewById(R.id.btnDelete).setEnabled(true);
                    Toast.makeText(VehicleDetailActivity.this, R.string.delete_error, Toast.LENGTH_SHORT).show();
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
                        Toast.makeText(VehicleDetailActivity.this, R.string.delete_ok, Toast.LENGTH_SHORT).show();
                        // The row is gone — drop its cached bitmap so a future vehicle reusing the
                        // id can't inherit this one's photo.
                        VehicleImages.evict(vehicle.id);
                        setResult(RESULT_OK);   // MainActivity reloads the list
                        finish();
                        return;
                    }
                    deleteInFlight = false;
                    findViewById(R.id.btnDelete).setEnabled(true);
                    Toast.makeText(VehicleDetailActivity.this, R.string.delete_error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** Binds the headline, subtitle and spec rows from the current {@link #vehicle}. */
    private void bindVehicleInfo() {
        ((TextView) findViewById(R.id.txtHeadline)).setText(join(vehicle.make, vehicle.model));
        ((TextView) findViewById(R.id.txtSubtitle)).setText(buildSubtitle(vehicle));

        String year = vehicle.year > 0 ? String.valueOf(vehicle.year) : "-";
        // The type's own icon, so the row reads as the vehicle's kind rather than as another field.
        VehicleType type = VehicleType.of(vehicle);
        bindSpec(R.id.rowType,    type.iconRes,          getString(R.string.spec_type),    getString(type.labelRes));
        bindSpec(R.id.rowYear,    R.drawable.ic_year,    getString(R.string.spec_year),    year);
        bindSpec(R.id.rowPlate,   R.drawable.ic_plate,   getString(R.string.spec_plate),   orDash(vehicle.licensePlate));
        bindSpec(R.id.rowMileage, R.drawable.ic_mileage, getString(R.string.spec_mileage), formatKm(vehicle.mileage));
        bindSpec(R.id.rowFuel,    R.drawable.ic_fuel,    getString(R.string.spec_fuel),    orDash(vehicle.fuelType));
        bindSpec(R.id.rowVin,     R.drawable.ic_vin,     getString(R.string.spec_vin),     orDash(vehicle.vin));
        bindSpec(R.id.rowColor,   R.drawable.ic_color,   getString(R.string.spec_color),   orDash(vehicle.color));

        bindDocumentsRow();
    }

    /**
     * Puts a warning glyph on the documents row when a declared expiry date is due or past.
     *
     * <p>Only the two declared dates feed this. The vignette is deliberately left out: it needs a
     * network call this screen doesn't make, and a badge that silently ignores one of three documents
     * while looking authoritative is worse than no badge. So the absence of a warning means "nothing
     * <em>you told us</em> is expiring", never "this car is road-legal" — which is also why there is
     * no green/OK state here, only the warning or nothing.
     */
    private void bindDocumentsRow() {
        ImageView warning = findViewById(R.id.detailDocWarning);
        ComplianceStatus worst = ComplianceStatus.declared(vehicle);

        if (worst == null || worst == ComplianceStatus.OK) {
            warning.setVisibility(View.GONE);
            return;
        }
        warning.setVisibility(View.VISIBLE);
        // Tint on the card background, so the status_*_text flavour — the fill colours are for badges
        // with white text on top and are unreadable used this way on the dark scheme.
        warning.setImageTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, worst.textColorRes)));
    }

    /**
     * Fetches this vehicle's photo. The response may carry either transport — {@code imageBase64}
     * (photos the user uploaded, stored in the DB) or {@code imageName} (a bundled drawable) — so
     * both are read and handed to {@link VehicleImages#apply}, which prefers the base64 one.
     *
     * <p>A base64 body is exactly the payload shape this server truncates — a 1024x768 photo is a
     * couple of MB — so <b>a parse failure is retried here, not just an I/O failure</b>: a cut
     * payload usually arrives as syntactically broken JSON (unterminated string), which used to
     * fall straight through to the placeholder without a single retry. Every failure path logs
     * under the {@code VehicleImage} tag with the byte counts needed to tell them apart.
     */
    private void fetchPhoto(String id, ImageView target, int attempt) {
        HttpUrl url = HttpUrl.parse(IMAGE_URL).newBuilder()
                .addQueryParameter("id", id)
                .build();
        Request request = new Request.Builder().url(url).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("VehicleImage", "GET /vehicles/image failed (attempt " + attempt + ")", e);
                if (attempt < IMAGE_MAX_ATTEMPTS) {
                    fetchPhoto(id, target, attempt + 1);
                }
                // else: keep the placeholder already shown
            }

            @Override
            public void onResponse(Call call, Response response) {
                String name = null;
                String base64 = null;
                try (Response r = response) {
                    if (!r.isSuccessful()) {
                        Log.e("VehicleImage", "HTTP " + r.code() + " (attempt " + attempt + ")");
                        return;   // not a transport glitch; retrying won't help
                    }
                    if (r.body() != null) {
                        // Length of what actually arrived vs. what the server promised: unequal
                        // means the response was cut short, which is the known server-side bug.
                        String body = r.body().string();
                        Log.i("VehicleImage", "body=" + body.length() + " chars, Content-Length="
                                + r.header("Content-Length") + " (attempt " + attempt + ")");
                        JSONObject o = new JSONObject(body);
                        name = o.optString("imageName", "");
                        base64 = o.optString("imageBase64", "");
                        Log.i("VehicleImage", "imageName='" + name + "', imageBase64="
                                + base64.length() + " chars");
                    }
                } catch (IOException e) {
                    Log.e("VehicleImage", "read failed — body cut short (attempt " + attempt + ")", e);
                } catch (JSONException e) {
                    // Truncation mid-payload lands here, not in the IOException branch: the socket
                    // closed cleanly, the JSON just stops in the middle of the base64 string.
                    Log.e("VehicleImage", "parse failed — body likely truncated (attempt " + attempt + ")", e);
                }

                if (name == null) {
                    if (attempt < IMAGE_MAX_ATTEMPTS) {
                        fetchPhoto(id, target, attempt + 1);
                    } else {
                        Log.e("VehicleImage", "giving up after " + IMAGE_MAX_ATTEMPTS
                                + " attempts; keeping placeholder");
                    }
                    return;
                }
                if (name.isEmpty() && base64.isEmpty()) {
                    return;   // nothing to show; don't replace what's already on screen
                }
                final String imageName = name;
                final String imageBase64 = base64;
                runOnUiThread(() -> {
                    // Drop the cached bitmap first — the payload here may be a different (larger)
                    // rendition than whatever the list row cached under this id.
                    VehicleImages.evict(vehicle.id);
                    VehicleImages.apply(VehicleDetailActivity.this, target,
                            imageBase64, imageName, vehicle.id, VehicleType.of(vehicle));
                });
            }
        });
    }

    // ---- Maintenance schedule ------------------------------------------------

    /**
     * TEMPORARY preview data — remove together with {@link #USE_SAMPLE_MAINTENANCE} once the
     * server endpoint exists. Figures are derived from the current mileage so the preview shows
     * an Overdue, a Due-soon, and two OK cards regardless of the vehicle.
     */
    private List<MaintenanceItem> buildSampleMaintenance(int current) {
        List<MaintenanceItem> list = new ArrayList<>();
        list.add(sample("Oil & oil filter",          current - 15000, current - 2000));  // overdue
        list.add(sample("Brake pads",                current - 19000, current + 1000));  // due soon
        list.add(sample("Air filter & cabin filter", current - 8000,  current + 22000)); // ok
        list.add(sample("Timing belt",               current - 30000, current + 60000)); // ok (plenty)
        return list;
    }

    private MaintenanceItem sample(String name, int last, int next) {
        MaintenanceItem m = new MaintenanceItem();
        m.name = name;
        m.lastChangeMileage = Math.max(0, last);
        m.nextChangeMileage = Math.max(0, next);
        return m;
    }

    private void fetchMaintenance(String id, int currentMileage, int attempt) {
        HttpUrl url = HttpUrl.parse(MAINTENANCE_URL).newBuilder()
                .addQueryParameter("id", id)
                .build();
        Request request = new Request.Builder().url(url).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Maintenance", "GET /vehicles/maintenance failed (attempt " + attempt + ")", e);
                if (attempt < MAINTENANCE_MAX_ATTEMPTS) {
                    fetchMaintenance(id, currentMileage, attempt + 1);
                    return;
                }
                renderMaintenance(new ArrayList<>(), currentMileage);   // give up -> empty state
            }

            @Override
            public void onResponse(Call call, Response response) {
                List<MaintenanceItem> items = null;
                boolean retriable = false;
                try (Response r = response) {
                    if (r.isSuccessful() && r.body() != null) {
                        items = MaintenanceItem.listFromJson(r.body().string());
                    }
                } catch (IOException e) {
                    retriable = true;   // truncated body — worth retrying
                    Log.e("Maintenance", "read failed (attempt " + attempt + ")", e);
                } catch (JSONException e) {
                    Log.e("Maintenance", "parse failed", e);
                }

                if (items == null) {
                    if (retriable && attempt < MAINTENANCE_MAX_ATTEMPTS) {
                        fetchMaintenance(id, currentMileage, attempt + 1);
                        return;
                    }
                    renderMaintenance(new ArrayList<>(), currentMileage);   // no data -> empty state
                    return;
                }
                renderMaintenance(items, currentMileage);
            }
        });
    }

    private void renderMaintenance(List<MaintenanceItem> items, int currentMileage) {
        runOnUiThread(() -> {
            findViewById(R.id.maintenanceProgress).setVisibility(View.GONE);
            if (swipe != null) {
                swipe.setRefreshing(false);   // every fetch path ends here, success or give-up
            }
            LinearLayout container = findViewById(R.id.maintenanceContainer);
            View empty = findViewById(R.id.historyEmpty);

            if (items == null || items.isEmpty()) {
                container.setVisibility(View.GONE);
                empty.setVisibility(View.VISIBLE);
                return;
            }

            empty.setVisibility(View.GONE);
            container.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(this);
            for (MaintenanceItem item : items) {
                View card = inflater.inflate(R.layout.item_maintenance_card, container, false);
                bindMaintenanceCard(card, item, currentMileage);
                container.addView(card);
            }
            container.setVisibility(View.VISIBLE);
        });
    }

    private void bindMaintenanceCard(View card, MaintenanceItem item, int currentMileage) {
        TextView name = card.findViewById(R.id.txtItemName);
        TextView status = card.findViewById(R.id.txtStatus);
        TextView last = card.findViewById(R.id.txtLastChange);
        TextView next = card.findViewById(R.id.txtNextChange);
        TextView remaining = card.findViewById(R.id.txtRemaining);
        TextView cost = card.findViewById(R.id.txtCost);
        LinearProgressIndicator progress = card.findViewById(R.id.progressService);

        // Wired before the early return below: a record with incomplete mileage data is exactly the
        // kind a user wants to delete, so it must not lose its delete button.
        ImageButton btnDeleteRecord = card.findViewById(R.id.btnDeleteRecord);
        boolean deletable = item.id != null && !item.id.isEmpty();
        btnDeleteRecord.setVisibility(deletable ? View.VISIBLE : View.GONE);
        if (deletable) {
            btnDeleteRecord.setOnClickListener(v -> confirmDeleteRecord(item));
        }

        // Same reason: notes and price don't depend on the mileage figures, so they're bound before
        // the "not enough data" bail-out — a record with only a note would otherwise show nothing.
        ImageButton btnNotes = card.findViewById(R.id.btnNotes);
        boolean hasNotes = item.notes != null && !item.notes.trim().isEmpty();
        btnNotes.setVisibility(hasNotes ? View.VISIBLE : View.GONE);
        if (hasNotes) {
            btnNotes.setOnClickListener(v -> showNotes(item));
        }

        // Also before the bail-out: a record can carry a receipt without usable mileage figures.
        ImageButton btnDocument = card.findViewById(R.id.btnDocument);
        boolean hasDocument = item.documentId != null && !item.documentId.trim().isEmpty();
        btnDocument.setVisibility(hasDocument ? View.VISIBLE : View.GONE);
        if (hasDocument) {
            btnDocument.setOnClickListener(v -> openDocument(item));
        }

        boolean hasCost = item.cost >= 0;   // negative = the server sent no price for this record
        cost.setVisibility(hasCost ? View.VISIBLE : View.GONE);
        if (hasCost) {
            cost.setText(getString(R.string.record_cost, formatMoney(item.cost)));
        }

        name.setText(orDash(item.name));
        last.setText(item.lastChangeMileage > 0 ? formatKm(item.lastChangeMileage) : "-");
        next.setText(item.nextChangeMileage > 0 ? formatKm(item.nextChangeMileage) : "-");

        int interval = item.nextChangeMileage - item.lastChangeMileage;
        if (item.nextChangeMileage <= 0 || interval <= 0) {
            // Not enough data to compute progress/status — just show the figures.
            progress.setVisibility(View.GONE);
            remaining.setVisibility(View.GONE);
            status.setVisibility(View.GONE);
            return;
        }

        int remainingKm = item.nextChangeMileage - currentMileage;
        int pct = (int) Math.round(100.0 * (currentMileage - item.lastChangeMileage) / interval);
        pct = Math.max(0, Math.min(100, pct));
        progress.setProgressCompat(pct, false);

        // interval > 0 and nextChangeMileage > 0 are guaranteed above, so this is non-null.
        MaintenanceStatus st = MaintenanceStatus.of(
                item.lastChangeMileage, item.nextChangeMileage, currentMileage);
        String remainText = remainingKm <= 0
                ? getString(R.string.km_overdue, formatKm(-remainingKm))
                : getString(R.string.km_remaining, formatKm(remainingKm));

        int color = ContextCompat.getColor(this, st.colorRes);
        progress.setIndicatorColor(color);
        status.getBackground().mutate().setTint(color);
        status.setText(st.labelRes);
        remaining.setText(remainText);
        remaining.setTextColor(color);
    }

    /**
     * Shows one record's notes in a dialog rather than on the card: notes are up to 200 characters
     * of free text, which would push the mileage figures of every other card off the screen.
     * Read-only — the record itself is edited by deleting and re-adding it.
     */
    /**
     * Opens the record's document photo full-screen. Passes only the id — never the bitmap, which would
     * blow the Binder transaction limit; {@code MaintenanceDocuments} caches by id, so the viewer gets
     * it instantly if it has been opened before.
     */
    private void openDocument(MaintenanceItem item) {
        Intent intent = new Intent(this, DocumentViewerActivity.class);
        intent.putExtra(DocumentViewerActivity.EXTRA_DOCUMENT_ID, item.documentId);
        intent.putExtra(DocumentViewerActivity.EXTRA_LABEL, item.name);
        startActivity(intent);
    }

    private void showNotes(MaintenanceItem item) {
        StringBuilder message = new StringBuilder();
        // The card shows neither of these, so the dialog is also where the record's date and price
        // are legible — otherwise the user has to export a PDF to see when a service happened.
        if (item.lastChangeDate != null && !item.lastChangeDate.trim().isEmpty()) {
            message.append(item.lastChangeDate.trim()).append('\n');
        }
        if (item.cost >= 0) {
            message.append(getString(R.string.record_cost, formatMoney(item.cost))).append('\n');
        }
        if (message.length() > 0) {
            message.append('\n');
        }
        message.append(item.notes == null ? "" : item.notes.trim());

        new MaterialAlertDialogBuilder(this)
                .setTitle(orDash(item.name))
                .setMessage(message.toString())
                .setPositiveButton(R.string.action_close, null)
                .show();
    }

    /** "180000" -> "180 000 км" (space thousands separator). */
    private String formatKm(int km) {
        return String.format(Locale.US, "%,d", km).replace(',', ' ') + " км";
    }

    /**
     * "1234.5" -> "1 234,50 €." — Bulgarian convention: space for thousands, comma for decimals.
     * Formatted in {@code Locale.US} and swapped by hand (the same trick {@link #formatKm} uses), so
     * the output can't change with the device locale while the rest of the screen stays Bulgarian.
     */
    private String formatMoney(double value) {
        String formatted = String.format(Locale.US, "%,.2f", value)
                .replace(',', ' ')
                .replace('.', ',');
        return formatted + " " + getString(R.string.currency_suffix);
    }

    /** Fills one spec row (icon + label + value) inside the specs card. */
    private void bindSpec(int rowId, int iconRes, String label, String value) {
        View row = findViewById(rowId);
        ((ImageView) row.findViewById(R.id.specIcon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.specLabel)).setText(label);
        ((TextView) row.findViewById(R.id.specValue)).setText(value);
    }

    /** Short one-liner under the headline, e.g. "2019 · Diesel · Blue". */
    private String buildSubtitle(Vehicle v) {
        StringBuilder sb = new StringBuilder();
        if (v.year > 0) append(sb, String.valueOf(v.year));
        append(sb, v.fuelType);
        append(sb, v.color);
        return sb.length() == 0 ? getString(R.string.vehicle_details) : sb.toString();
    }

    private void append(StringBuilder sb, String part) {
        if (part == null || part.isEmpty()) return;
        if (sb.length() > 0) sb.append("  ·  ");
        sb.append(part);
    }

    private String join(String a, String b) {
        return (orEmpty(a) + " " + orEmpty(b)).trim();
    }

    private String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private String orDash(String s) {
        return (s == null || s.isEmpty()) ? "-" : s;
    }
}
