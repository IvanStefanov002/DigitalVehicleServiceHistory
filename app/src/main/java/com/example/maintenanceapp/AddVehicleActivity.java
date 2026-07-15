package com.example.maintenanceapp;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class AddVehicleActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_vehicle);

        // Hide status bar
        WindowInsetsControllerCompat controller =
                ViewCompat.getWindowInsetsController(getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.statusBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );
        }

        ImageButton btnBack = findViewById(R.id.btnBack);
        EditText edtMake = findViewById(R.id.edtMake);
        EditText edtModel = findViewById(R.id.edtModel);
        Button btnSave = findViewById(R.id.btnSaveVehicle);

        // Back button closes activity
        btnBack.setOnClickListener(v -> finish());

        // Save button (for now, just Toast)
        btnSave.setOnClickListener(v -> {
            String make = edtMake.getText().toString().trim();
            String model = edtModel.getText().toString().trim();

            if (make.isEmpty() || model.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
                return;
            }

            // TODO: send to backend
            Toast.makeText(this, "Vehicle added: " + make + " " + model, Toast.LENGTH_SHORT).show();
            finish(); // close activity after save
        });
    }
}