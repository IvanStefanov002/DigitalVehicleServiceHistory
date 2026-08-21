package com.example.maintenanceapp;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.maintenanceapp.adapter.MaintenanceTypeAdapter;
import com.example.maintenanceapp.adapter.VehicleAdapter;
import com.example.maintenanceapp.model.MaintenanceItem;
import com.example.maintenanceapp.model.MaintenanceType;
import com.example.maintenanceapp.model.Vehicle;
import com.example.maintenanceapp.model.VignetteInfo;
import com.example.maintenanceapp.util.ApiClient;
import com.example.maintenanceapp.util.BiometricLock;
import com.example.maintenanceapp.util.ComplianceStatus;
import com.example.maintenanceapp.util.MaintenanceStatus;
import com.example.maintenanceapp.util.ScreenInsets;
import com.example.maintenanceapp.util.SwipeRefresh;
import com.example.maintenanceapp.util.VehicleType;
import com.example.maintenanceapp.work.ServiceReminders;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String VEHICLES_URL = "http://92.5.55.85:27778/vehicles";
    private static final String MAINTENANCE_URL = "http://92.5.55.85:27778/vehicles/maintenance";
    private static final String MAINTENANCE_TYPES_URL = "http://92.5.55.85:27778/maintenance/types";
    private static final String VIGNETTE_URL = "http://92.5.55.85:27778/vehicles/vignette";
    private static final int TYPES_MAX_ATTEMPTS = 3;
    // Per-vehicle reminder fetch retries (the C++ server intermittently truncates responses).
    private static final int STATUS_MAX_ATTEMPTS = 3;

    private OkHttpClient client;

    // In-memory cache of the user's vehicles. Refreshed in onResume (initial load and when
    // returning from AddVehicleActivity), NOT on every tab switch — tabs render from this cache.
    private final List<Vehicle> vehicles = new ArrayList<>();
    private VehicleAdapter homeAdapter;      // non-null while the Home tab is visible
    private RecyclerView homeRecycler;       // non-null while the Home tab is visible
    private TextView homeVehicleCount;       // header count on the Home tab
    private View homeEmptyState;             // "no vehicles" view on the Home tab
    // Fleet overview dashboard on the Home tab (replaces the old single banner).
    private View homeOverviewCard;           // whole card; hidden when the fleet is empty
    private TextView ovVehicleValue;         // vehicle count tile
    private TextView ovAttentionValue;       // "needs attention" count tile (tinted per severity)
    private View ovAllGood;                   // "all clear" row
    private View ovServiceRow;                // service category row
    private ImageView ovServiceIcon;
    private TextView ovServiceDetail;
    private View ovDocsRow;                    // documents category row
    private ImageView ovDocsIcon;
    private TextView ovDocsDetail;

    /**
     * Session cache of the service-type catalog. This is static reference data (names + intervals),
     * so it is fetched at most once per session and re-bound from memory on later visits to the
     * Поддръжка tab — same rule as the {@link #vehicles} cache. Without it, every tab tap fired a
     * GET, and each tap cost up to {@link #TYPES_MAX_ATTEMPTS} round-trips whenever the fetch
     * failed. Pull-to-refresh (see refreshMaintenanceTypes) is the way to invalidate it.
     */
    private final List<MaintenanceType> maintenanceTypes = new ArrayList<>();

    /** True while a catalog fetch is in flight, so rapid tab taps can't stack duplicate requests. */
    private boolean typesRequestInFlight;

    private MaintenanceTypeAdapter maintTypeAdapter;   // non-null while the Поддръжка tab is visible
    private ProgressBar maintTypesProgress;            // non-null while the Поддръжка tab is visible

    // Pull-to-refresh hosts. Each is non-null only while its own tab is on screen, so a late
    // callback can null-check instead of touching a detached layout.
    private SwipeRefreshLayout homeSwipe;
    private SwipeRefreshLayout maintSwipe;
    private SwipeRefreshLayout profileSwipe;
    private View profileRoot;      // kept so a refresh can re-bind the fleet stats

    // Per-vehicle worst service status (vehicle id -> status), loaded async after the vehicle list.
    // Feeds both the row badges and the reminder banner. Mutated only on the main thread.
    private final Map<String, MaintenanceStatus> vehicleStatuses = new HashMap<>();
    // Bumped on each status reload so late callbacks from a superseded load are ignored.
    private int statusLoadGeneration;

    // Per-vehicle worst *document* status (vehicle id -> status): винетка + ГТП + ГО. Separate map
    // and separate generation counter from the service statuses above, because the two loads finish
    // independently and sharing a counter would let one cancel the other's callbacks.
    private final Map<String, ComplianceStatus> vehicleDocStatuses = new HashMap<>();
    private int docLoadGeneration;

    /** True while the biometric switch is being flipped back in code, so its listener ignores it. */
    private boolean revertingBiometricSwitch;

    // Launches Add Vehicle and reloads the list only when a vehicle was actually added.
    private final ActivityResultLauncher<Intent> addVehicleLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    loadVehicles();
                }
            });

    // Opens a vehicle's detail; reloads the list when it returns RESULT_OK (e.g. after an edit).
    private final ActivityResultLauncher<Intent> vehicleDetailLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    loadVehicles();
                }
            });

    // Android 13+ gates notifications behind a runtime permission. The result is deliberately
    // ignored: the reminder job is scheduled either way, and simply posts nothing while the
    // permission is refused — so a "no" costs the user nothing and can be reversed in Settings.
    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> { });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        client = ApiClient.get(this);

        // Service reminders: create the channel, ask for the permission once, and make sure the
        // daily background check is scheduled (a no-op when it already is).
        ServiceReminders.ensureChannel(this);
        requestNotificationPermissionIfNeeded();
        ServiceReminders.schedule(this);

        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);

        // Insets — the bottom nav owns the navigation-bar inset itself, or the whole bar would be
        // lifted off the bottom edge (very visible with three-button navigation).
        ScreenInsets.applyWithBottomBar(findViewById(R.id.main));

        // Hide status bar
//        WindowInsetsControllerCompat controller =
//                ViewCompat.getWindowInsetsController(getWindow().getDecorView());
//        if (controller != null) {
//            controller.hide(WindowInsetsCompat.Type.statusBars());
//            controller.setSystemBarsBehavior(
//                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
//            );
//        }

        // Bottom navigation
        bottomNav.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_vehicles) {
                showVehicles();
                return true;
            } else if (item.getItemId() == R.id.nav_maintenance) {
                showMaintenance();
                return true;
            } else if (item.getItemId() == R.id.nav_profile) {
                showProfile();
                return true;
            }
            return false;
        });

        // Preselect VEHICLES
        bottomNav.setSelectedItemId(R.id.nav_vehicles);

        // Load the vehicle list once for this session; tabs render from the cache after this.
        loadVehicles();
    }

    /**
     * Asks for POST_NOTIFICATIONS on Android 13+, where it's a runtime permission. Below that it's
     * granted at install time, and asking would throw. The system shows the dialog only once —
     * after a refusal this call is a silent no-op, so it's safe on every start.
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    /** Fetches vehicles from the server into the in-memory cache and refreshes whichever tab is visible. */
    private void loadVehicles() {
        fetchVehicles(list -> {
            // null means the fetch failed after all retries — keep the cached list instead of
            // clearing it (see deliverError).
            if (list != null) {
                vehicles.clear();
                vehicles.addAll(list);
                if (homeAdapter != null) {
                    homeAdapter.setVehicles(vehicles);
                    refreshHomeSummary();
                }
                if (profileRoot != null) {
                    bindProfileStats(profileRoot);   // fleet stats are derived from this list
                }
                // Refresh both per-vehicle badge sets for the new list. Two independent loads:
                // service status needs a request per vehicle, document status is already known for
                // the declared dates and only the vignette half goes to the network.
                loadMaintenanceStatuses();
                loadDocumentStatuses();
            }
            stopVehicleRefreshIndicators();
        }, true);
    }

    /** Clears the pull-to-refresh spinner on whichever vehicle-backed tab is currently visible. */
    private void stopVehicleRefreshIndicators() {
        if (homeSwipe != null) {
            homeSwipe.setRefreshing(false);
        }
        if (profileSwipe != null) {
            profileSwipe.setRefreshing(false);
        }
    }

    // used and execute when "Vehicles" navigation button is clicked
    private void showVehicles() {
        FrameLayout container = findViewById( R.id.contentContainer );
        container.removeAllViews();
        clearMaintenanceRefs();     // leaving the Поддръжка tab
        clearProfileRefs();         // leaving the Профил tab

        View homeView = getLayoutInflater()
                .inflate(R.layout.home_content, container, false);

        container.addView( homeView );

        RecyclerView recycler = homeView.findViewById( R.id.vehiclesRecycler );
        ExtendedFloatingActionButton fab = homeView.findViewById( R.id.fabAddVehicle );

        recycler.setLayoutManager( new LinearLayoutManager( this ) );

        // Render from the in-memory cache; no network call on tab switches.
        homeRecycler = recycler;
        homeVehicleCount = homeView.findViewById( R.id.txtHomeCount );
        homeEmptyState = homeView.findViewById( R.id.homeEmpty );
        homeOverviewCard = homeView.findViewById( R.id.homeOverviewCard );
        ovVehicleValue = homeView.findViewById( R.id.ovVehicleValue );
        ovAttentionValue = homeView.findViewById( R.id.ovAttentionValue );
        ovAllGood = homeView.findViewById( R.id.ovAllGood );
        ovServiceRow = homeView.findViewById( R.id.ovServiceRow );
        ovServiceIcon = homeView.findViewById( R.id.ovServiceIcon );
        ovServiceDetail = homeView.findViewById( R.id.ovServiceDetail );
        ovDocsRow = homeView.findViewById( R.id.ovDocsRow );
        ovDocsIcon = homeView.findViewById( R.id.ovDocsIcon );
        ovDocsDetail = homeView.findViewById( R.id.ovDocsDetail );
        homeAdapter = new VehicleAdapter( vehicles, this::openVehicleDetail );
        recycler.setAdapter( homeAdapter );
        // Apply any service statuses already loaded for this session (badges + banner).
        homeAdapter.setStatuses( vehicleStatuses );
        homeAdapter.setDocStatuses( vehicleDocStatuses );
        refreshHomeSummary();
        refreshOverview();

        homeSwipe = homeView.findViewById( R.id.homeSwipe );
        SwipeRefresh.theme( homeSwipe );
        homeSwipe.setOnRefreshListener( this::loadVehicles );

        fab.setOnClickListener(v ->
                addVehicleLauncher.launch(new Intent(this, AddVehicleActivity.class))
        );
    }

    /** Updates the header count and toggles the empty state on the Home tab. */
    private void refreshHomeSummary() {
        int n = vehicles.size();
        if (homeVehicleCount != null) {
            // A car-only fleet keeps the natural wording; anything else has to go generic, since
            // counting a motorcycle as an „автомобил“ is wrong in the one place the user counts.
            boolean carsOnly = true;
            for (Vehicle v : vehicles) {
                if (VehicleType.of(v) != VehicleType.CAR) {
                    carsOnly = false;
                    break;
                }
            }
            homeVehicleCount.setText(getResources().getQuantityString(
                    carsOnly ? R.plurals.vehicle_count : R.plurals.vehicle_count_mixed, n, n));
        }
        boolean empty = vehicles.isEmpty();
        if (homeEmptyState != null) {
            homeEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (homeRecycler != null) {
            homeRecycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
        if (homeOverviewCard != null) {
            // An overview reading "0 / all good" over an empty-state illustration is noise; the
            // dashboard only makes sense once there's a fleet to summarise.
            homeOverviewCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    // ---- Service reminders (Автопарк tab) ------------------------------------

    /**
     * Loads each vehicle's maintenance and derives a worst-case service status for the row badges
     * and the reminder banner. One request per vehicle, each retried a few times on the flaky
     * server (see {@link #STATUS_MAX_ATTEMPTS}); if it still fails, that vehicle is left without a
     * badge rather than blocking the others. A generation counter drops results from a superseded
     * reload (e.g. after adding a vehicle) so stale statuses never land on the new list.
     */
    private void loadMaintenanceStatuses() {
        vehicleStatuses.clear();
        final int generation = ++statusLoadGeneration;
        if (homeAdapter != null) {
            homeAdapter.setStatuses(vehicleStatuses);   // drop stale badges immediately
        }
        refreshOverview();

        for (Vehicle v : vehicles) {
            if (v.id != null && !v.id.isEmpty()) {
                fetchVehicleStatus(v.id, v.mileage, generation, 1);
            }
        }
    }

    private void fetchVehicleStatus(String id, int currentMileage, int generation, int attempt) {
        HttpUrl url = HttpUrl.parse(MAINTENANCE_URL).newBuilder()
                .addQueryParameter("id", id)
                .build();
        Request request = new Request.Builder().url(url).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // Connection-level failure — retry, then give up (leave the vehicle unbadged).
                if (attempt < STATUS_MAX_ATTEMPTS) {
                    fetchVehicleStatus(id, currentMileage, generation, attempt + 1);
                    return;
                }
                Log.e("Reminders", "GET /vehicles/maintenance failed for id=" + id, e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                MaintenanceStatus status = null;
                boolean retriable = false;
                try (Response r = response) {
                    if (r.isSuccessful() && r.body() != null) {
                        List<MaintenanceItem> items = MaintenanceItem.listFromJson(r.body().string());
                        status = MaintenanceStatus.worst(items, currentMileage);
                        // A successful parse with no computable status = genuinely no records: don't retry.
                    } else {
                        retriable = true;   // non-2xx — may be transient
                    }
                } catch (IOException e) {
                    retriable = true;       // truncated body ("unexpected end of stream") — worth retrying
                    Log.e("Reminders", "status read failed for id=" + id + " (attempt " + attempt + ")", e);
                } catch (JSONException e) {
                    Log.e("Reminders", "status parse failed for id=" + id, e);
                }

                if (status == null) {
                    if (retriable && attempt < STATUS_MAX_ATTEMPTS) {
                        fetchVehicleStatus(id, currentMileage, generation, attempt + 1);
                    }
                    return;   // no records / gave up -> no badge
                }
                final MaintenanceStatus result = status;
                runOnUiThread(() -> applyVehicleStatus(id, result, generation));
            }
        });
    }

    /** Applies one vehicle's status on the main thread, ignoring results from a superseded reload. */
    private void applyVehicleStatus(String id, MaintenanceStatus status, int generation) {
        if (generation != statusLoadGeneration) {
            return;   // a newer reload has started; this result is stale
        }
        vehicleStatuses.put(id, status);
        if (homeAdapter != null) {
            homeAdapter.setStatuses(vehicleStatuses);
        }
        refreshOverview();
    }

    // ---- Document reminders (Автопарк tab) -----------------------------------

    /**
     * Derives each vehicle's worst document status for the row badges and the reminder banner.
     *
     * <p>Two-phase on purpose. The ГТП and ГО dates arrive with {@code GET /vehicles}, so those
     * badges are applied <b>synchronously</b> and appear with the list rather than a network round
     * later. Only the vignette needs a request, and it merely upgrades a badge that is already
     * correct for everything else — so a fleet whose vignette check fails still shows its declared
     * expiries instead of nothing.
     */
    private void loadDocumentStatuses() {
        vehicleDocStatuses.clear();
        final int generation = ++docLoadGeneration;

        for (Vehicle v : vehicles) {
            applyDocStatus(v, null, generation);   // declared dates only, no network
        }
        for (Vehicle v : vehicles) {
            // Exempt types (motorcycles) are skipped rather than checked: the authority would
            // correctly answer "no vignette" and that would badge the bike red permanently. Their
            // badge stays derived from the declared ГТП/ГО dates alone. See
            // VehicleType.requiresVignette().
            if (v.id != null && !v.id.isEmpty() && VehicleType.of(v).requiresVignette()) {
                fetchVignetteStatus(v, generation, 1);
            }
        }
    }

    /**
     * One {@code GET /vehicles/vignette?id=} per vehicle, mirroring how {@link #fetchVehicleStatus}
     * fans out. Cheap because the backend serves these from a ~24h cache; a vehicle whose check
     * fails keeps whatever its declared dates said rather than blocking the others.
     */
    private void fetchVignetteStatus(Vehicle vehicle, int generation, int attempt) {
        HttpUrl url = HttpUrl.parse(VIGNETTE_URL).newBuilder()
                .addQueryParameter("id", vehicle.id)
                .build();
        Request request = new Request.Builder().url(url).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (attempt < STATUS_MAX_ATTEMPTS) {
                    fetchVignetteStatus(vehicle, generation, attempt + 1);
                    return;
                }
                Log.e("Reminders", "GET /vehicles/vignette failed for id=" + vehicle.id, e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                VignetteInfo info = null;
                boolean retriable = false;
                try (Response r = response) {
                    if (r.isSuccessful() && r.body() != null) {
                        info = VignetteInfo.fromJson(r.body().string());
                    } else {
                        // Non-2xx = the backend could not reach the authority. "Unknown", not
                        // "no vignette" — so it stays unbadged instead of going red.
                        retriable = true;
                    }
                } catch (IOException e) {
                    retriable = true;   // truncated body
                    Log.e("Reminders", "vignette read failed for id=" + vehicle.id, e);
                } catch (JSONException e) {
                    Log.e("Reminders", "vignette parse failed for id=" + vehicle.id, e);
                }

                if (info == null) {
                    if (retriable && attempt < STATUS_MAX_ATTEMPTS) {
                        fetchVignetteStatus(vehicle, generation, attempt + 1);
                    }
                    return;   // keep the declared-only badge
                }
                final VignetteInfo result = info;
                runOnUiThread(() -> applyDocStatus(vehicle, result, generation));
            }
        });
    }

    /**
     * Recomputes one vehicle's document badge from its declared dates plus (optionally) a vignette
     * answer. The declared half is derived fresh from the {@link Vehicle} each time rather than
     * cached, which keeps a single source of truth and makes this safe to call twice per vehicle.
     */
    private void applyDocStatus(Vehicle vehicle, VignetteInfo info, int generation) {
        if (generation != docLoadGeneration) {
            return;   // a newer reload has started; this result is stale
        }
        if (vehicle.id == null || vehicle.id.isEmpty()) {
            return;
        }
        ComplianceStatus status = ComplianceStatus.worst(
                ComplianceStatus.declared(vehicle),
                ComplianceStatus.ofVignette(info));
        if (status == null) {
            vehicleDocStatuses.remove(vehicle.id);
        } else {
            vehicleDocStatuses.put(vehicle.id, status);
        }
        if (homeAdapter != null) {
            homeAdapter.setDocStatuses(vehicleDocStatuses);
        }
        refreshOverview();
    }

    /**
     * Fills the fleet overview dashboard: the two stat tiles and the per-category alert rows.
     *
     * <p>Alerts are split by <b>category</b> — service vs documents — rather than merged into one
     * count, because an overdue oil change and an expired vignette are different problems with
     * different fixes. Each category row is tinted by its own worst severity (red if anything is
     * overdue, else amber) using the {@code status_*_text} colours, which read correctly on the card
     * background in both themes. The "needs attention" tile counts <em>vehicles</em> with any issue
     * in either category, so a car with two problems is still one line in the user's mind.
     */
    private void refreshOverview() {
        if (homeOverviewCard == null) {
            return;   // Home tab not currently visible
        }

        int serviceOverdue = 0, serviceDue = 0;
        for (MaintenanceStatus st : vehicleStatuses.values()) {
            if (st == MaintenanceStatus.OVERDUE) {
                serviceOverdue++;
            } else if (st == MaintenanceStatus.DUE) {
                serviceDue++;
            }
        }
        int docOverdue = 0, docDue = 0;
        for (ComplianceStatus st : vehicleDocStatuses.values()) {
            if (st == ComplianceStatus.OVERDUE) {
                docOverdue++;
            } else if (st == ComplianceStatus.DUE) {
                docDue++;
            }
        }

        // Vehicles (not alerts) needing attention: the union of the two maps at DUE/OVERDUE.
        int attention = 0;
        boolean anyOverdue = false;
        for (Vehicle v : vehicles) {
            if (v.id == null) {
                continue;
            }
            MaintenanceStatus svc = vehicleStatuses.get(v.id);
            ComplianceStatus doc = vehicleDocStatuses.get(v.id);
            boolean svcHit = svc == MaintenanceStatus.OVERDUE || svc == MaintenanceStatus.DUE;
            boolean docHit = doc == ComplianceStatus.OVERDUE || doc == ComplianceStatus.DUE;
            if (svcHit || docHit) {
                attention++;
            }
            if (svc == MaintenanceStatus.OVERDUE || doc == ComplianceStatus.OVERDUE) {
                anyOverdue = true;
            }
        }

        ovVehicleValue.setText(String.valueOf(vehicles.size()));
        ovAttentionValue.setText(String.valueOf(attention));
        // Colour the attention count only when there is something to attend to.
        int attentionColor = attention == 0
                ? themeColor(com.google.android.material.R.attr.colorOnSurface)
                : ContextCompat.getColor(this,
                        anyOverdue ? R.color.status_overdue_text : R.color.status_due_text);
        ovAttentionValue.setTextColor(attentionColor);

        boolean serviceHit = serviceOverdue > 0 || serviceDue > 0;
        boolean docHit = docOverdue > 0 || docDue > 0;

        ovAllGood.setVisibility(!serviceHit && !docHit ? View.VISIBLE : View.GONE);

        bindCategoryRow(ovServiceRow, ovServiceIcon, ovServiceDetail, serviceHit,
                serviceOverdue, serviceDue, R.string.ov_svc_overdue, R.string.ov_svc_due);
        bindCategoryRow(ovDocsRow, ovDocsIcon, ovDocsDetail, docHit,
                docOverdue, docDue, R.string.ov_doc_overdue, R.string.ov_doc_due);
    }

    /** Shows or hides one overview category row and paints it by its worst severity. */
    private void bindCategoryRow(View row, ImageView icon, TextView detail, boolean visible,
                                 int overdue, int due, int overdueRes, int dueRes) {
        if (!visible) {
            row.setVisibility(View.GONE);
            return;
        }
        row.setVisibility(View.VISIBLE);
        StringBuilder sb = new StringBuilder();
        if (overdue > 0) {
            sb.append(getString(overdueRes, overdue));
        }
        if (due > 0) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append(getString(dueRes, due));
        }
        // status_*_text, not the fills: this colours text/icon on the card background, where a
        // fill-grade dark red is unreadable in the dark scheme.
        int color = ContextCompat.getColor(this,
                overdue > 0 ? R.color.status_overdue_text : R.color.status_due_text);
        detail.setText(sb.toString());
        detail.setTextColor(color);
        icon.setColorFilter(color);
    }

    /** Resolves a theme colour attribute to an int (used for the neutral attention-tile colour). */
    private int themeColor(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (!getTheme().resolveAttribute(attr, tv, true)) {
            return ContextCompat.getColor(this, R.color.status_ok_text);
        }
        return tv.resourceId != 0 ? ContextCompat.getColor(this, tv.resourceId) : tv.data;
    }

    private void openVehicleDetail(Vehicle vehicle) {
        Intent intent = new Intent(this, VehicleDetailActivity.class);
        intent.putExtra(VehicleDetailActivity.EXTRA_VEHICLE, vehicle);
        vehicleDetailLauncher.launch(intent);
    }

    private void showMaintenance() {
        FrameLayout container = findViewById(R.id.contentContainer);
        container.removeAllViews();
        clearHomeRefs();     // leaving the Home tab
        clearProfileRefs();  // leaving the Профил tab

        View view = getLayoutInflater().inflate(R.layout.maintenance_content, container, false);
        container.addView(view);

        RecyclerView recycler = view.findViewById(R.id.typesRecycler);
        recycler.setLayoutManager(new GridLayoutManager(this, 2));
        recycler.setItemAnimator(null);   // avoid stale bounds when a card expands/collapses
        maintTypeAdapter = new MaintenanceTypeAdapter();
        recycler.setAdapter(maintTypeAdapter);
        maintTypesProgress = view.findViewById( R.id.typesProgress );

        if ( !maintenanceTypes.isEmpty() ) {
            // Already loaded this session: render from cache, no network call.
            maintTypesProgress.setVisibility( View.GONE );
            maintTypeAdapter.setTypes( maintenanceTypes );
        } else if ( typesRequestInFlight ) {
            // A fetch from an earlier tap is still running; it will populate this adapter when it
            // lands (deliverTypes reads the fields, not a captured view).
            maintTypesProgress.setVisibility( View.VISIBLE );
        } else {
            maintTypesProgress.setVisibility( View.VISIBLE );
            typesRequestInFlight = true;
            fetchMaintenanceTypes( 1 );
        }

        maintSwipe = view.findViewById( R.id.maintSwipe );
        SwipeRefresh.theme( maintSwipe );
        maintSwipe.setOnRefreshListener( this::refreshMaintenanceTypes );

        // Oil advisor. Read-only, so plain startActivity — nothing there changes the fleet, and the
        // vehicle list travels the same way the add-service form gets it.
        view.findViewById( R.id.cardOilAdvisor ).setOnClickListener(v -> {
            if ( vehicles.isEmpty() ) {
                Toast.makeText( this, R.string.maint_no_vehicles, Toast.LENGTH_LONG ).show();
                return;
            }
            // Cars only — see VehicleType.supportsOilAdvisor(). The filter happens here as well as
            // inside the advisor so a fleet of motorcycles gets a straight explanation instead of a
            // screen whose vehicle picker is empty.
            ArrayList<Vehicle> cars = new ArrayList<>();
            for ( Vehicle vehicle : vehicles ) {
                if ( VehicleType.of( vehicle ).supportsOilAdvisor() ) {
                    cars.add( vehicle );
                }
            }
            if ( cars.isEmpty() ) {
                Toast.makeText( this, R.string.oil_no_cars, Toast.LENGTH_LONG ).show();
                return;
            }
            Intent intent = new Intent( this, OilRecommendationActivity.class );
            intent.putExtra( OilRecommendationActivity.EXTRA_VEHICLES, cars );
            startActivity( intent );
        });

        ExtendedFloatingActionButton fab = view.findViewById( R.id.fabAddMaintenance );
        fab.setOnClickListener(v -> {
            Intent intent = new Intent( this, AddMaintenanceActivity.class );
            intent.putExtra( AddMaintenanceActivity.EXTRA_VEHICLES, new ArrayList<>(vehicles) );
            startActivity( intent );
        });
    }

    /**
     * Pull-to-refresh on the Поддръжка tab: drops the session cache and refetches the catalog. This
     * is the only way to pick up a server-side catalog change without restarting the app.
     */
    private void refreshMaintenanceTypes() {
        if (typesRequestInFlight) {
            return;   // already loading; deliverTypes will stop the spinner when it lands
        }
        maintenanceTypes.clear();
        typesRequestInFlight = true;
        fetchMaintenanceTypes(1);
    }

    /**
     * Loads the service-type catalog into the Поддръжка list. On failure/truncation it retries,
     * then falls back to a built-in list so the tab is never empty (the server catalog is the
     * source of truth once {@code GET /maintenance/types} is implemented).
     */
    private void fetchMaintenanceTypes( int attempt ) {
        Request request = new Request.Builder().url( MAINTENANCE_TYPES_URL ).get().build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure( Call call, IOException e ) {
                Log.e("Maintenance", "GET /maintenance/types failed (attempt " + attempt + ")", e);
                if ( attempt < TYPES_MAX_ATTEMPTS ) {
                    fetchMaintenanceTypes( attempt + 1 );
                    return;
                }
                deliverTypes( fallbackTypes() );
            }

            @Override
            public void onResponse( Call call, Response response ) {
                List<MaintenanceType> types = null;
                boolean retriable = false;
                try ( Response r = response ) {
                    if ( r.isSuccessful() && r.body() != null ) {
                        types = parseTypes(r.body().string());
                    }
                } catch ( IOException e ) {
                    retriable = true;   // truncated body — worth retrying
                    Log.e("Maintenance", "types read failed (attempt " + attempt + ")", e);
                } catch (JSONException e) {
                    Log.e("Maintenance", "types parse failed", e);
                }

                if ( types == null || types.isEmpty() ) {
                    if ( retriable && attempt < TYPES_MAX_ATTEMPTS ) {
                        fetchMaintenanceTypes( attempt + 1 );
                        return;
                    }
                    deliverTypes( fallbackTypes() );
                    return;
                }
                deliverTypes( types );
            }
        });
    }

    /**
     * Caches the catalog for the rest of the session and, if the Поддръжка tab is still on screen,
     * renders it. The fallback list is cached too — otherwise every tab tap would re-run the full
     * retry sequence against the missing endpoint. A fresh app start picks up the real catalog once
     * {@code GET /maintenance/types} exists.
     */
    private void deliverTypes(List<MaintenanceType> types) {
        runOnUiThread(() -> {
            typesRequestInFlight = false;
            maintenanceTypes.clear();
            maintenanceTypes.addAll(types);

            if (maintSwipe != null) {
                maintSwipe.setRefreshing(false);
            }
            if (maintTypeAdapter == null) {
                return;   // user switched tabs while the request was in flight
            }
            if (maintTypesProgress != null) {
                maintTypesProgress.setVisibility(View.GONE);
            }
            maintTypeAdapter.setTypes(maintenanceTypes);
        });
    }

    private List<MaintenanceType> parseTypes(String body) throws JSONException {

        // used to store types as a list
        List<MaintenanceType> list = new ArrayList<>();

        // start parse
        JSONArray arr = new JSONObject(body).optJSONArray("types");
        if ( arr != null ) {
            for ( int i = 0; i < arr.length(); i++ ) {
                JSONObject o = arr.optJSONObject(i); // type

                // validate the object
                if (o == null) continue;

                // take parameters from object
                String name = o.optString( "name", "" );
                if ( !name.isEmpty() ) {
                    list.add( new MaintenanceType(name,
                            o.optInt( "defaultIntervalKm", 0 ),
                            o.optString( "description", "" ) ) );
                }
            }
        }

        // return types as a list
        return list;
    }

    /** Placeholder catalog shown until GET /maintenance/types exists; intervals are indicative. */
    private List<MaintenanceType> fallbackTypes() {
        List<MaintenanceType> list = new ArrayList<>();
        list.add(new MaintenanceType("Смяна на масло и филтър", 15000));
        list.add(new MaintenanceType("Въздушен и кабинен филтър", 30000));
        list.add(new MaintenanceType("Спирачни накладки", 40000));
        list.add(new MaintenanceType("Спирачни дискове", 80000));
        list.add(new MaintenanceType("Ангренажен ремък", 120000,
                "Скъсване на ремъка може да доведе до тежка повреда на двигателя. "
                        + "Спазвайте интервала стриктно и проверявайте състоянието му."));
        list.add(new MaintenanceType("Гуми", 50000));
        return list;
    }

    /** Drops references to Поддръжка-tab views so an in-flight catalog fetch can't touch a
     *  detached layout. The cached {@link #maintenanceTypes} list is kept. */
    private void clearMaintenanceRefs() {
        maintTypeAdapter = null;
        maintTypesProgress = null;
        maintSwipe = null;
    }

    /** Drops references to Профил-tab views so a late vehicles callback can't touch them. */
    private void clearProfileRefs() {
        profileSwipe = null;
        profileRoot = null;
    }

    /** Drops references to Home-tab views so stale updates don't touch a detached layout. */
    private void clearHomeRefs() {
        homeAdapter = null;
        homeRecycler = null;
        homeVehicleCount = null;
        homeEmptyState = null;
        homeOverviewCard = null;
        ovVehicleValue = null;
        ovAttentionValue = null;
        ovAllGood = null;
        ovServiceRow = null;
        ovServiceIcon = null;
        ovServiceDetail = null;
        ovDocsRow = null;
        ovDocsIcon = null;
        ovDocsDetail = null;
        homeSwipe = null;
    }

    // used and execute when "Profile" navigation button is clicked
    private void showProfile() {
        FrameLayout container = findViewById( R.id.contentContainer );
        container.removeAllViews();
        clearHomeRefs();            // leaving the Home tab
        clearMaintenanceRefs();     // leaving the Поддръжка tab

        View profileView = getLayoutInflater().inflate( R.layout.profile_content, container, false );

        container.addView( profileView );

        // Load user info (replace with real backend data)
        TextView txtFullName = profileView.findViewById( R.id.txtFullName ); // full name
        TextView txtEmail = profileView.findViewById( R.id.txtEmail ); // email
        TextView txtUsername = profileView.findViewById( R.id.txtUsername ); // @username
        ImageView imgProfile = profileView.findViewById( R.id.imgProfile ); // profile image

        // Load the profile saved at login time
        SharedPreferences prefs = getSharedPreferences( "auth", MODE_PRIVATE );
        txtFullName.setText( prefs.getString( "fullName", "" ) );
        txtEmail.setText( prefs.getString( "email", "" ) );
        String username = prefs.getString( "username", "" );
        txtUsername.setText( username.isEmpty() ? "" : "@" + username );

        // Fleet stats + app version come from the in-memory cache / package info (no network call here).
        bindProfileStats( profileView );

        profileRoot = profileView;
        profileSwipe = profileView.findViewById( R.id.profileSwipe );
        SwipeRefresh.theme( profileSwipe );
        // Refreshes the vehicle list, which is what the fleet stats are computed from. The
        // name/email/avatar come from the cached login response and can't be refetched here.
        profileSwipe.setOnRefreshListener( this::loadVehicles );

        // Profile image: decode the base64 returned by the server (falls back to the
        // default drawable in the layout if it's missing or can't be decoded).
        String imageBase64 = prefs.getString( "profileImageBase64", "" );
        if ( !imageBase64.isEmpty() ) {
            try {
                byte[] bytes = Base64.decode( imageBase64, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    imgProfile.setImageBitmap(bitmap);
                }
            } catch (IllegalArgumentException e) {
                // malformed base64 — keep the default icon
            }
        }

        bindBiometricSetting(profileView);

        // Logout button — confirm first so an accidental tap doesn't sign the user out.
        Button btnLogout = profileView.findViewById(R.id.btnLogoutProfile);
        btnLogout.setOnClickListener(v -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.logout_confirm_title)
                .setMessage(R.string.logout_confirm_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.profile_logout, (dialog, which) -> {
                    getSharedPreferences("auth", MODE_PRIVATE).edit().clear().apply();
                    // Stop the background check too — otherwise it keeps waking up for a signed-out
                    // user, and the next user could be silenced by this one's notification state.
                    ServiceReminders.cancel(this);
                    startActivity(new Intent(this, LoginActivity.class));
                    finish();
                })
                .show());
    }

    /**
     * Wires the "lock with biometrics" switch on the Profile tab.
     *
     * <p>Turning it <b>on</b> runs a prompt first and only saves the preference once the user has
     * actually authenticated — enabling a lock you can't open would strand you on the login screen
     * next launch. Turning it <b>off</b> doesn't prompt: whoever is looking at this screen is
     * already past the gate, so asking again would protect nothing.
     */
    private void bindBiometricSetting(View profileView) {
        MaterialSwitch switchBiometric = profileView.findViewById(R.id.switchBiometric);
        TextView summary = profileView.findViewById(R.id.txtBiometricSummary);

        if (!BiometricLock.isAvailable(this)) {
            switchBiometric.setChecked(false);
            switchBiometric.setEnabled(false);
            summary.setText(R.string.bio_setting_unavailable);
            return;
        }

        // setChecked before attaching the listener, so restoring the saved state doesn't fire it
        // and re-prompt every time the user opens the Profile tab.
        switchBiometric.setChecked(BiometricLock.isEnabled(this));
        switchBiometric.setOnCheckedChangeListener((button, isChecked) -> {
            if (revertingBiometricSwitch) {
                return;   // we flipped it back ourselves; not a user action
            }
            if (!isChecked) {
                BiometricLock.setEnabled(this, false);
                Toast.makeText(this, R.string.bio_disabled, Toast.LENGTH_SHORT).show();
                return;
            }
            BiometricLock.prompt(this, new BiometricLock.Callback() {
                @Override
                public void onSuccess() {
                    BiometricLock.setEnabled(MainActivity.this, true);
                    Toast.makeText(MainActivity.this, R.string.bio_enabled, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onFailure(String message) {
                    // Couldn't prove it works, so leave the lock off and put the switch back —
                    // guarded, or the programmatic flip would run the listener's "off" branch and
                    // stack a second toast on top of the error.
                    BiometricLock.setEnabled(MainActivity.this, false);
                    revertingBiometricSwitch = true;
                    switchBiometric.setChecked(false);
                    revertingBiometricSwitch = false;
                    if (message != null) {
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                }
            });
        });
    }

    /** Fills the fleet-stats card and the app-version row on the Profile tab from local data only. */
    private void bindProfileStats(View profileView) {
        int count = vehicles.size();
        long totalKm = 0;
        for (Vehicle v : vehicles) {
            totalKm += Math.max(0, v.mileage);
        }

        TextView statVehicles = profileView.findViewById(R.id.txtStatVehicles);
        TextView statMileage = profileView.findViewById(R.id.txtStatMileage);
        statVehicles.setText(String.valueOf(count));
        statMileage.setText(getString(R.string.profile_km, formatThousands(totalKm)));

        TextView appVersion = profileView.findViewById(R.id.txtAppVersion);
        appVersion.setText(appVersionName());
    }

    /** "12345" -> "12 345" (space thousands separator), matching the vehicle detail screen. */
    private String formatThousands(long value) {
        return String.format(java.util.Locale.US, "%,d", value).replace(',', ' ');
    }

    /** App versionName from the package manager (BuildConfig isn't generated in this module). */
    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    // Total attempts for the (idempotent) vehicles GET. The C++ backend intermittently
    // truncates responses ("unexpected end of stream"); retrying works around it until fixed.
    private static final int VEHICLES_MAX_ATTEMPTS = 3;

    /**
     * Fetches the logged-in user's vehicles from the backend and delivers them on the UI thread.
     * Transient network/truncation failures are retried; on final failure the callback receives
     * an empty list (and, if showErrors, a toast).
     */
    private void fetchVehicles(Consumer<List<Vehicle>> onResult, boolean showErrors) {
        fetchVehiclesAttempt(onResult, showErrors, 1);
    }

    private void fetchVehiclesAttempt(Consumer<List<Vehicle>> onResult, boolean showErrors, int attempt) {
        // No username param — the backend identifies the user from the Bearer token (see ApiClient).
        Request request = new Request.Builder().url(VEHICLES_URL).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Vehicles", "GET /vehicles failed (attempt " + attempt + ")", e);
                if (attempt < VEHICLES_MAX_ATTEMPTS) {
                    fetchVehiclesAttempt(onResult, showErrors, attempt + 1);
                    return;
                }
                deliverError("Could not load vehicles: " + e.getMessage(), showErrors, onResult);
            }

            @Override
            public void onResponse(Call call, Response response) {
                List<Vehicle> vehicles = new ArrayList<>();
                String error = null;
                boolean retriable = false;
                try (Response r = response) {
                    String bodyStr = r.body() != null ? r.body().string() : "";
                    Log.d("Vehicles", "GET /vehicles -> HTTP " + r.code() + " (attempt " + attempt + ")");

                    if (r.isSuccessful()) {
                        JSONObject json = new JSONObject(bodyStr);
                        JSONArray arr = json.optJSONArray("vehicles");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                vehicles.add(Vehicle.fromJson(arr.getJSONObject(i)));
                            }
                        }
                    } else {
                        error = "Server returned HTTP " + r.code();
                    }
                } catch (IOException e) {
                    // truncated/interrupted body — worth retrying
                    error = "Load failed: " + e.getMessage();
                    retriable = true;
                    Log.e("Vehicles", "GET /vehicles read failed (attempt " + attempt + ")", e);
                } catch (JSONException e) {
                    error = "Load failed: " + e.getMessage();
                    Log.e("Vehicles", "GET /vehicles parse failed (attempt " + attempt + ")", e);
                }

                if (retriable && attempt < VEHICLES_MAX_ATTEMPTS) {
                    fetchVehiclesAttempt(onResult, showErrors, attempt + 1);
                    return;
                }
                final String err = error;
                final List<Vehicle> result = vehicles;
                runOnUiThread(() -> {
                    if (err != null && showErrors) {
                        Toast.makeText(MainActivity.this, err, Toast.LENGTH_LONG).show();
                    }
                    onResult.accept(result);
                });
            }
        });
    }

    /**
     * Reports a failed vehicles fetch. Delivers {@code null} — <em>not</em> an empty list — so the
     * caller can tell "the user has no vehicles" apart from "we couldn't reach the server" and keep
     * showing cached data. Handing back an empty list here used to blank the fleet out on any
     * transient failure, which pull-to-refresh would have made a routine occurrence.
     */
    private void deliverError(String message, boolean showErrors, Consumer<List<Vehicle>> onResult) {
        runOnUiThread(() -> {
            if (showErrors) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
            onResult.accept(null);
        });
    }

}