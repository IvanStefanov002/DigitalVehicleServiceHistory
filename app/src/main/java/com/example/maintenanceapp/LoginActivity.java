package com.example.maintenanceapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

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

public class LoginActivity extends AppCompatActivity {

    private static final String API_URL =
            "https://vehiclemaintenanceplatform.onrender.com/users/login";

    private EditText edtUsername, edtPassword;
    private Button btnLogin;

    private final OkHttpClient client = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        //Auto-skip Login if already logged in
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        if (prefs.contains("username")) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        edtUsername = findViewById(R.id.edtUsername);
        edtPassword = findViewById(R.id.edtPassword);
        btnLogin = findViewById(R.id.btnLogin);

        btnLogin.setOnClickListener(v -> login());
    }

    private void login() {
        String username = edtUsername.getText().toString().trim();
        String password = edtPassword.getText().toString().trim();

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this,
                    "Please enter username and password",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONObject json = new JSONObject();
            json.put("username", username);
            json.put("password", password);

            RequestBody body = RequestBody.create(
                    json.toString(),
                    MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(API_URL)
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {

                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() ->
                            Toast.makeText(LoginActivity.this,
                                    "Server error",
                                    Toast.LENGTH_SHORT).show()
                    );
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {

                    if (response.isSuccessful()) {

                        // Optional: parse response JSON
                        String responseBody = response.body().string();
                        JSONObject data = null;
                        try {
                            data = new JSONObject(responseBody);
                        } catch (JSONException e) {
                            throw new RuntimeException(e);
                        }

                        // Save logged user (SharedPreferences = Android localStorage)
                        SharedPreferences prefs =
                                getSharedPreferences("auth", MODE_PRIVATE);

                        try {
                            prefs.edit()
                                    .putString("username", data.getString("username"))
                                    .apply();
                        } catch (JSONException e) {
                            throw new RuntimeException(e);
                        }

                        runOnUiThread(() -> {
                            Intent intent =
                                    new Intent(LoginActivity.this, MainActivity.class);
                            startActivity(intent);
                            finish();
                        });

                    } else {
                        runOnUiThread(() ->
                                Toast.makeText(LoginActivity.this,
                                        "Invalid credentials",
                                        Toast.LENGTH_SHORT).show()
                        );
                    }
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}