/*
 * MainActivity.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

package com.example.maintenanceapp;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
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
import com.example.maintenanceapp.util.Api;
import com.example.maintenanceapp.util.ApiClient;
import com.example.maintenanceapp.util.BiometricLock;
import com.example.maintenanceapp.util.ComplianceStatus;
import com.example.maintenanceapp.util.MaintenanceStatus;
import com.example.maintenanceapp.util.ScreenInsets;
import com.example.maintenanceapp.util.MaintenanceTypeEditor;
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

    private static final int TYPES_MAX_ATTEMPTS = 3;
    private static final int STATUS_MAX_ATTEMPTS = 3;

    private TextView homeVehicleCount, ovVehicleValue, ovAttentionValue, ovServiceDetail, ovDocsDetail;
    private View homeEmptyState, homeOverviewCard, ovAllGood, ovServiceRow, ovDocsRow, profileRoot;
    private final List<MaintenanceType> maintenanceTypes = new ArrayList<>();
    private final List<Vehicle> vehicles = new ArrayList<>();
    private ImageView ovServiceIcon, ovDocsIcon;
    private VehicleAdapter homeAdapter;
    private RecyclerView homeRecycler;
    private OkHttpClient client;

    /** True while a catalog fetch is in flight, so rapid tab taps can't stack duplicate requests. */
    private boolean typesRequestInFlight;

    private final Map<String, ComplianceStatus> vehicleDocStatuses = new HashMap<>();
    private final Map<String, MaintenanceStatus> vehicleStatuses = new HashMap<>();
    private boolean revertingNotificationsSwitch, revertingBiometricSwitch;
    private SwipeRefreshLayout homeSwipe, maintSwipe, profileSwipe;
    private int statusLoadGeneration, docLoadGeneration;
    private MaintenanceTypeAdapter maintTypeAdapter;
    private ProgressBar maintTypesProgress;
    private View maintSuggestionsCard;
    private ImageButton btnHiddenTypes;

    /** True while the biometric switch is being flipped back in code, so its listener ignores it. */

    private final ActivityResultLauncher<Intent> addVehicleLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    loadVehicles();
                }
            });

    private final ActivityResultLauncher<Intent> vehicleDetailLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    loadVehicles();
                }
            });

    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> { });

    private final ActivityResultLauncher<String> notificationToggle =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    enableNotifications();
                    return;
                }
                ServiceReminders.setEnabled(this, false);
                refreshNotificationSetting();
                Toast.makeText(this, R.string.notif_permission_denied, Toast.LENGTH_LONG).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        client = ApiClient.get(this);
        ServiceReminders.ensureChannel(this);
        requestNotificationPermissionIfNeeded();
        ServiceReminders.schedule(this);

        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        ScreenInsets.applyWithBottomBar(findViewById(R.id.main));

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

        bottomNav.setSelectedItemId(R.id.nav_vehicles);
        loadVehicles();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (!ServiceReminders.isEnabled(this)) {
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
            if (list != null) {
                vehicles.clear();
                vehicles.addAll(list);
                if (homeAdapter != null) {
                    homeAdapter.setVehicles(vehicles);
                    refreshHomeSummary();
                }
                if (profileRoot != null) {
                    bindProfileStats(profileRoot);
                }
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

    private void showVehicles() {
        FrameLayout container = findViewById( R.id.contentContainer );
        container.removeAllViews();
        clearMaintenanceRefs();
        clearProfileRefs();

        View homeView = getLayoutInflater()
                .inflate(R.layout.home_content, container, false);

        container.addView( homeView );

        RecyclerView recycler = homeView.findViewById( R.id.vehiclesRecycler );
        ExtendedFloatingActionButton fab = homeView.findViewById( R.id.fabAddVehicle );

        recycler.setLayoutManager( new LinearLayoutManager( this ) );

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
            homeOverviewCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

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
        HttpUrl url = HttpUrl.parse(Api.MAINTENANCE).newBuilder()
                .addQueryParameter("id", id)
                .build();
        Request request = new Request.Builder().url(url).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
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
                    return;
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

    private void loadDocumentStatuses() {
        vehicleDocStatuses.clear();
        final int generation = ++docLoadGeneration;

        for (Vehicle v : vehicles) {
            applyDocStatus(v, null, generation);
        }
        for (Vehicle v : vehicles) {
            if (v.id != null && !v.id.isEmpty() && VehicleType.of(v).requiresVignette()) {
                fetchVignetteStatus(v, generation, 1);
            }
        }
    }

    private void fetchVignetteStatus(Vehicle vehicle, int generation, int attempt) {
        HttpUrl url = HttpUrl.parse(Api.VEHICLE_VIGNETTE).newBuilder()
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
                    return;
                }
                final VignetteInfo result = info;
                runOnUiThread(() -> applyDocStatus(vehicle, result, generation));
            }
        });
    }

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
        clearHomeRefs();
        clearProfileRefs();

        View view = getLayoutInflater().inflate(R.layout.maintenance_content, container, false);
        container.addView(view);

        RecyclerView recycler = view.findViewById(R.id.typesRecycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setItemAnimator(null);
        maintTypeAdapter = new MaintenanceTypeAdapter();
        maintTypeAdapter.setOnEditListener(type ->
                MaintenanceTypeEditor.editType(this, type, this::onCatalogChanged));
        maintTypeAdapter.setOnHideListener(type ->
                MaintenanceTypeEditor.hideType(this, type, this::onCatalogChanged));
        maintTypeAdapter.setOnAddListener(() ->
                MaintenanceTypeEditor.createType(this, this::onCatalogChanged));
        recycler.setAdapter(maintTypeAdapter);
        maintTypesProgress = view.findViewById( R.id.typesProgress );

        maintSuggestionsCard = view.findViewById( R.id.cardSuggestions );
        view.findViewById( R.id.sgHeader ).setOnClickListener(v -> showSuggestions());
        btnHiddenTypes = view.findViewById( R.id.btnHiddenTypes );
        btnHiddenTypes.setOnClickListener(v -> showHiddenTypes());
        refreshHiddenTypes();

        if ( !maintenanceTypes.isEmpty() ) {
            maintTypesProgress.setVisibility( View.GONE );
            maintTypeAdapter.setTypes( visibleTypes() );
        } else if ( typesRequestInFlight ) {
            maintTypesProgress.setVisibility( View.VISIBLE );
        } else {
            maintTypesProgress.setVisibility( View.VISIBLE );
            typesRequestInFlight = true;
            fetchMaintenanceTypes( 1 );
        }

        maintSwipe = view.findViewById( R.id.maintSwipe );
        SwipeRefresh.theme( maintSwipe );
        maintSwipe.setOnRefreshListener( this::refreshMaintenanceTypes );
        view.findViewById( R.id.cardOilAdvisor ).setOnClickListener(v -> {
            if ( vehicles.isEmpty() ) {
                Toast.makeText( this, R.string.maint_no_vehicles, Toast.LENGTH_LONG ).show();
                return;
            }
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

    /** Pull-to-refresh on the Поддръжка tab. */
    private void refreshMaintenanceTypes() {
        if (typesRequestInFlight) {
            return;
        }
        maintenanceTypes.clear();
        typesRequestInFlight = true;
        fetchMaintenanceTypes(1);
    }

    private void fetchMaintenanceTypes( int attempt ) {
        HttpUrl url = HttpUrl.parse( Api.MAINTENANCE_TYPES );
        if ( url != null ) {
            url = url.newBuilder().addQueryParameter( "includeArchived", "1" ).build();
        }
        Request request = new Request.Builder()
                .url( url == null ? Api.MAINTENANCE_TYPES : url.toString() )
                .get().build();
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
            maintTypeAdapter.setTypes(visibleTypes());
            refreshSuggestions();
            refreshHiddenTypes();
        });
    }

    private List<MaintenanceType> visibleTypes() {
        List<MaintenanceType> visible = new ArrayList<>();
        for (MaintenanceType type : maintenanceTypes) {
            if (!type.archived) {
                visible.add(type);
            }
        }
        return visible;
    }

    private List<MaintenanceType> hiddenTypes() {
        List<MaintenanceType> hidden = new ArrayList<>();
        for (MaintenanceType type : maintenanceTypes) {
            if (type.archived) {
                hidden.add(type);
            }
        }
        return hidden;
    }

    private void refreshHiddenTypes() {
        if (btnHiddenTypes == null) {
            return;
        }
        int count = hiddenTypes().size();
        btnHiddenTypes.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        btnHiddenTypes.setContentDescription(getString(R.string.mt_hidden_count, count));
        androidx.appcompat.widget.TooltipCompat.setTooltipText(btnHiddenTypes,
                getString(R.string.mt_hidden_count, count));
    }

    private void showHiddenTypes() {
        List<MaintenanceType> hidden = hiddenTypes();
        if (hidden.isEmpty()) {
            return;
        }
        String[] labels = new String[hidden.size()];
        for (int i = 0; i < hidden.size(); i++) {
            labels[i] = hidden.get(i).name;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.mt_hidden_title)
                .setItems(labels, (d, which) ->
                        MaintenanceTypeEditor.restoreType(this, hidden.get(which), this::onCatalogChanged))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void onCatalogChanged() {
        maintenanceTypes.clear();
        typesRequestInFlight = true;
        if (maintTypesProgress != null) {
            maintTypesProgress.setVisibility(View.VISIBLE);
        }
        fetchMaintenanceTypes(1);
    }

    private List<MaintenanceType> suggestedTypes() {
        List<MaintenanceType> suggested = new ArrayList<>();
        for (MaintenanceType type : maintenanceTypes) {
            if (type.suggested && !type.archived
                    && (type.suggestedIntervalKm > 0 || type.suggestedIntervalMonths > 0)) {
                suggested.add(type);
            }
        }
        return suggested;
    }

    private void refreshSuggestions() {
        if (maintSuggestionsCard == null) {
            return;
        }
        maintSuggestionsCard.setVisibility(suggestedTypes().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showSuggestions() {
        List<MaintenanceType> suggested = suggestedTypes();
        if (suggested.isEmpty()) {
            return;
        }

        View content = getLayoutInflater().inflate(R.layout.dialog_suggested_intervals, null, false);
        LinearLayout list = content.findViewById(R.id.sgList);

        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog
                .MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sg_title)
                .setView(content)
                .setPositiveButton(R.string.sg_close, null)
                .create();

        for (MaintenanceType type : suggested) {
            View row = getLayoutInflater().inflate(R.layout.item_suggested_interval, list, false);
            TextView name = row.findViewById(R.id.txtSgName);
            TextView interval = row.findViewById(R.id.txtSgInterval);
            TextView yours = row.findViewById(R.id.txtSgYours);
            View apply = row.findViewById(R.id.btnSgApply);

            name.setText(type.name);
            interval.setText(MaintenanceTypeEditor.intervalLabel(getResources(),
                    type.suggestedIntervalKm, type.suggestedIntervalMonths));

            boolean differs = type.overridden();
            yours.setVisibility(differs ? View.VISIBLE : View.GONE);
            apply.setVisibility(differs ? View.VISIBLE : View.GONE);
            if (differs) {
                yours.setText(getString(R.string.sg_yours,
                        MaintenanceTypeEditor.intervalLabel(getResources(),
                                type.defaultIntervalKm, type.defaultIntervalMonths)));
                apply.setOnClickListener(v -> {
                    dialog.dismiss();
                    MaintenanceTypeEditor.applySuggestion(this, type, this::onCatalogChanged);
                });
            }
            list.addView(row);
        }

        dialog.show();
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
                    MaintenanceType type = new MaintenanceType(name,
                            o.optInt( "defaultIntervalKm", 0 ),
                            o.optString( "description", "" ) );
                    type.id = o.isNull("id") ? "" : o.optString("id", "");
                    type.defaultIntervalMonths = o.optInt( "defaultIntervalMonths", 0 );
                    type.suggestedIntervalKm = o.optInt( "suggestedIntervalKm", type.defaultIntervalKm );
                    type.suggestedIntervalMonths = o.optInt( "suggestedIntervalMonths", type.defaultIntervalMonths );
                    type.suggested = o.optBoolean( "suggested", false );
                    type.custom = o.optBoolean( "custom", false );
                    type.archived = o.optBoolean( "archived", o.optBoolean( "hidden", false ) );
                    list.add( type );
                }
            }
        }

        // return types as a list
        return list;
    }

    /** Placeholder catalog shown until GET /maintenance/types exists; intervals are indicative. */
    private List<MaintenanceType> fallbackTypes() {
        List<MaintenanceType> list = new ArrayList<>();
        list.add(new MaintenanceType("Смяна на масло и филтър", 0));
        list.add(new MaintenanceType("Въздушен и кабинен филтър", 0));
        list.add(new MaintenanceType("Спирачни накладки", 0));
        list.add(new MaintenanceType("Спирачни дискове", 0));
        list.add(new MaintenanceType("Ангренажен ремък", 0));
        list.add(new MaintenanceType("Охладителна течност", 0));
        list.add(new MaintenanceType("Гуми", 0));
        return list;
    }

    private void clearMaintenanceRefs() {
        maintTypeAdapter = null;
        maintTypesProgress = null;
        maintSwipe = null;
        maintSuggestionsCard = null;
        btnHiddenTypes = null;
    }

    private void clearProfileRefs() {
        profileSwipe = null;
        profileRoot = null;
    }

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

    /** used and execute when "Profile" navigation button is clicked */
    private void showProfile() {
        FrameLayout container = findViewById( R.id.contentContainer );
        container.removeAllViews();
        clearHomeRefs();
        clearMaintenanceRefs();

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
        bindProfileStats( profileView );

        profileRoot = profileView;
        profileSwipe = profileView.findViewById( R.id.profileSwipe );
        SwipeRefresh.theme( profileSwipe );
        profileSwipe.setOnRefreshListener( this::loadVehicles );

        String imageBase64 = prefs.getString( "profileImageBase64", "" );
        if ( !imageBase64.isEmpty() ) {
            try {
                byte[] bytes = Base64.decode( imageBase64, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    imgProfile.setImageBitmap(bitmap);
                }
            } catch (IllegalArgumentException e) {}
        }

        bindBiometricSetting(profileView);
        bindNotificationSetting(profileView);

        // Logout button — confirm first so an accidental tap doesn't sign the user out.
        Button btnLogout = profileView.findViewById(R.id.btnLogoutProfile);
        btnLogout.setOnClickListener(v -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.logout_confirm_title)
                .setMessage(R.string.logout_confirm_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.profile_logout, (dialog, which) -> {
                    getSharedPreferences("auth", MODE_PRIVATE).edit().clear().apply();
                    ServiceReminders.cancel(this);
                    startActivity(new Intent(this, LoginActivity.class));
                    finish();
                })
                .show());
    }

    /** biometrics enable/disable switch */
    private void bindBiometricSetting(View profileView) {
        MaterialSwitch switchBiometric = profileView.findViewById(R.id.switchBiometric);
        TextView summary = profileView.findViewById(R.id.txtBiometricSummary);

        if (!BiometricLock.isAvailable(this)) {
            switchBiometric.setChecked(false);
            switchBiometric.setEnabled(false);
            summary.setText(R.string.bio_setting_unavailable);
            return;
        }

        switchBiometric.setChecked(BiometricLock.isEnabled(this));
        switchBiometric.setOnCheckedChangeListener((button, isChecked) -> {
            if (revertingBiometricSwitch) {
                return;
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

    /** notification enable/disable switch */
    private void bindNotificationSetting(View profileView) {
        MaterialSwitch switchNotifications = profileView.findViewById(R.id.switchNotifications);
        TextView summary = profileView.findViewById(R.id.txtNotificationsSummary);

        // setChecked before attaching the listener, so restoring the saved state doesn't fire it
        // and re-run the whole enable flow every time the user opens the Profile tab.
        switchNotifications.setChecked(ServiceReminders.isEnabled(this));
        summary.setText(notificationSummaryRes());

        switchNotifications.setOnCheckedChangeListener((button, isChecked) -> {
            if (revertingNotificationsSwitch) {
                return;   // we flipped it back ourselves; not a user action
            }
            if (!isChecked) {
                ServiceReminders.setEnabled(this, false);
                // Also drops the stored "what was last notified" signature, so re-enabling starts
                // clean rather than staying silent about something flagged before the switch off.
                ServiceReminders.cancel(this);
                refreshNotificationSetting();
                Toast.makeText(this, R.string.notif_disabled, Toast.LENGTH_SHORT).show();
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationToggle.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;   // the launcher's callback finishes the job
            }
            enableNotifications();
        });
    }

    /** Saves the preference, starts the daily check, and says so - or explains why it can't. */
    private void enableNotifications() {
        ServiceReminders.setEnabled(this, true);
        ServiceReminders.schedule(this);
        refreshNotificationSetting();

        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Toast.makeText(this, R.string.notif_enabled, Toast.LENGTH_SHORT).show();
        } else {
            promptOpenNotificationSettings();
        }
    }

    /** Re-reads both gates and repaints the switch + summary, if the Profile tab is on screen. */
    private void refreshNotificationSetting() {
        MaterialSwitch switchNotifications = findViewById(R.id.switchNotifications);
        TextView summary = findViewById(R.id.txtNotificationsSummary);
        if (switchNotifications == null || summary == null) {
            return;   // user has switched tabs; nothing to repaint
        }
        boolean enabled = ServiceReminders.isEnabled(this);
        if (switchNotifications.isChecked() != enabled) {
            // Guarded, or the programmatic flip would re-enter the listener and stack a second
            // toast on top of the one already being shown.
            revertingNotificationsSwitch = true;
            switchNotifications.setChecked(enabled);
            revertingNotificationsSwitch = false;
        }
        summary.setText(notificationSummaryRes());
    }

    private int notificationSummaryRes() {
        boolean blocked = !NotificationManagerCompat.from(this).areNotificationsEnabled();
        return blocked && ServiceReminders.isEnabled(this)
                ? R.string.notif_setting_blocked
                : R.string.notif_setting_summary;
    }

    private void promptOpenNotificationSettings() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notif_setting_title)
                .setMessage(R.string.notif_setting_blocked)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.notif_open_settings, (dialog, which) -> {
                    Intent intent;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    } else {
                        intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(android.net.Uri.fromParts("package", getPackageName(), null));
                    }
                    startActivity(intent);
                })
                .show();
    }

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

    private String formatThousands(long value) {
        return String.format(java.util.Locale.US, "%,d", value).replace(',', ' ');
    }

    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static final int VEHICLES_MAX_ATTEMPTS = 3;
    private void fetchVehicles(Consumer<List<Vehicle>> onResult, boolean showErrors) {
        fetchVehiclesAttempt(onResult, showErrors, 1);
    }
    private void fetchVehiclesAttempt(Consumer<List<Vehicle>> onResult, boolean showErrors, int attempt) {
        // No username param — the backend identifies the user from the Bearer token (see ApiClient).
        Request request = new Request.Builder().url(Api.VEHICLES).get().build();

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

    private void deliverError(String message, boolean showErrors, Consumer<List<Vehicle>> onResult) {
        runOnUiThread(() -> {
            if (showErrors) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
            onResult.accept(null);
        });
    }

}