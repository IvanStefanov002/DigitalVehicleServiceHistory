/*
 * LoginActivity.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

package com.example.maintenanceapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.maintenanceapp.util.Api;
import com.example.maintenanceapp.util.ApiClient;
import com.example.maintenanceapp.util.BiometricLock;
import com.example.maintenanceapp.util.ScreenInsets;

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

    private static final int LOGIN_MAX_ATTEMPTS = 3;

    private EditText edtUsername, edtPassword;
    private Button btnLogin;
    private Button btnUnlock;
    private ProgressBar progressLogin;

    private OkHttpClient client;

    private final ActivityResultLauncher<Intent> registerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    return;
                }
                String username = result.getData().getStringExtra(RegisterActivity.EXTRA_USERNAME);
                if (username != null && !username.isEmpty()) {
                    edtUsername.setText(username);
                    edtPassword.requestFocus();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        boolean hasSession = prefs.contains("username");

        if (hasSession && !BiometricLock.isEnabled(this)) {
            goToMain();
            return;
        }

        setContentView(R.layout.activity_login);
        ScreenInsets.apply(findViewById(R.id.loginContent));

        client = ApiClient.get(this);
        edtUsername = findViewById(R.id.edtUsername);
        edtPassword = findViewById(R.id.edtPassword);
        btnLogin = findViewById(R.id.btnLogin);
        progressLogin = findViewById(R.id.progressLogin);
        btnUnlock = findViewById(R.id.btnUnlock);

        btnLogin.setOnClickListener(v -> login());
        btnUnlock.setOnClickListener(v -> promptUnlock());

        findViewById(R.id.btnGoToRegister).setOnClickListener(v ->
                registerLauncher.launch(new Intent(this, RegisterActivity.class)));

        if (hasSession) {
            btnUnlock.setVisibility(View.VISIBLE);
            promptUnlock();
        }
    }

    /** Asks for biometrics to release the saved session. */
    private void promptUnlock() {
        if (!BiometricLock.isAvailable(this)) {
            Toast.makeText(this, R.string.bio_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        BiometricLock.prompt(this, new BiometricLock.Callback() {
            @Override
            public void onSuccess() {
                goToMain();
            }

            @Override
            public void onFailure(String message) {
                if (message != null) {
                    Toast.makeText(LoginActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void login() {
        String username = edtUsername.getText().toString().trim();
        String password = edtPassword.getText().toString().trim();

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.login_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONObject json = new JSONObject();
            json.put("username", username);
            json.put("password", password);

            /** Prevent a second tap from firing another request while this one is in flight. */
            btnLogin.setEnabled(false);
            progressLogin.setVisibility(android.view.View.VISIBLE);
            attemptLogin(json.toString(), 1);

        } catch (JSONException e) {
            btnLogin.setEnabled(true);
            progressLogin.setVisibility(android.view.View.GONE);
            e.printStackTrace();
        }
    }

    private void attemptLogin(String bodyJson, int attempt) {
        RequestBody body = RequestBody.create(bodyJson, MediaType.parse("application/json"));
        Request request = new Request.Builder().url(Api.LOGIN).post(body).build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(Call call, IOException e) {
                // Connection-level failure — retry, then report.
                if (attempt < LOGIN_MAX_ATTEMPTS) {
                    attemptLogin(bodyJson, attempt + 1);
                    return;
                }
                runOnUiThread(() -> {
                    btnLogin.setEnabled(true);
                    progressLogin.setVisibility(android.view.View.GONE);
                    Toast.makeText(LoginActivity.this,
                            R.string.login_network_error,
                            Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) {
                boolean success = false;
                boolean networkError = false;
                JSONObject data = null;
                try (Response r = response) {
                    String responseBody = r.body() != null ? r.body().string() : "";
                    if (r.isSuccessful()) {
                        data = new JSONObject(responseBody);
                        success = true;
                    }
                } catch (IOException e) {
                    networkError = true;
                } catch (JSONException e) {
                    networkError = true;
                }

                if (success) {
                    /** Save logged user + profile info */
                    SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);

                    prefs.edit()
                            // Bearer token authorizes every subsequent request.
                            .putString("token", data.optString("token", ""))
                            .putString("username", data.optString("username"))
                            .putString("fullName", data.optString("fullName"))
                            .putString("email", data.optString("email"))
                            .putInt("vehicleCount", data.optInt("vehicleCount", 0))
                            .putString("profileImageBase64", data.optString("profileImageBase64", ""))
                            .apply();

                    runOnUiThread(() -> {
                        Intent intent =
                                new Intent(LoginActivity.this, MainActivity.class);
                        startActivity(intent);
                        finish();
                    });
                    return;
                }

                if (networkError && attempt < LOGIN_MAX_ATTEMPTS) {
                    attemptLogin(bodyJson, attempt + 1);
                    return;
                }

                final boolean wasNetworkError = networkError;
                runOnUiThread(() -> {
                    btnLogin.setEnabled(true);
                    progressLogin.setVisibility(android.view.View.GONE);
                    Toast.makeText(LoginActivity.this,
                            wasNetworkError ? R.string.login_network_error : R.string.login_invalid,
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
}