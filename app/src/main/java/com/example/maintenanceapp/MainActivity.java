package com.example.maintenanceapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.maintenanceapp.adapter.VehicleAdapter;
import com.example.maintenanceapp.model.Vehicle;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

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
            if (item.getItemId() == R.id.nav_home) {
                showHome();
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

        // Preselect HOME
        bottomNav.setSelectedItemId(R.id.nav_home);
    }

    private void showHome() {
        FrameLayout container = findViewById(R.id.contentContainer);
        container.removeAllViews();

        View homeView = getLayoutInflater()
                .inflate(R.layout.home_content, container, false);

        container.addView(homeView);

        RecyclerView recycler = homeView.findViewById(R.id.vehiclesRecycler);
        FloatingActionButton fab = homeView.findViewById(R.id.fabAddVehicle);

        recycler.setLayoutManager(new LinearLayoutManager(this));

        List<Vehicle> vehicles = new ArrayList<>();
        vehicles.add(new Vehicle("BMW", "320d"));
        vehicles.add(new Vehicle("Audi", "A4"));

        recycler.setAdapter(new VehicleAdapter(vehicles));

        fab.setOnClickListener(v ->
                startActivity(new Intent(this, AddVehicleActivity.class))
        );
    }

    private void showMaintenance() {
        FrameLayout container = findViewById(R.id.contentContainer);
        container.removeAllViews();
        // later: inflate maintenance layout
    }

    private void showProfile() {
        FrameLayout container = findViewById(R.id.contentContainer);
        container.removeAllViews();

        View profileView = getLayoutInflater()
                .inflate(R.layout.profile_content, container, false);

        container.addView(profileView);

        // Load user info (replace with real backend data)
        TextView txtFullName = profileView.findViewById(R.id.txtFullName);
        TextView txtEmail = profileView.findViewById(R.id.txtEmail);
        TextView txtVehicleCount = profileView.findViewById(R.id.txtVehicleCount);
        ImageView imgProfile = profileView.findViewById(R.id.imgProfile);

        // For demo
        txtFullName.setText("Ivan Stefanov");
        txtEmail.setText("ivan.stefanov@example.com");
        txtVehicleCount.setText("Number of vehicles: 3");

        // Logout button
        Button btnLogout = profileView.findViewById(R.id.btnLogoutProfile);
        btnLogout.setOnClickListener(v -> {
            getSharedPreferences("auth", MODE_PRIVATE).edit().clear().apply();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
    }
}