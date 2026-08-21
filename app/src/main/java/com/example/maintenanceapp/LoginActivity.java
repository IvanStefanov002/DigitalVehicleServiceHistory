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

    private static final String API_URL =
            "http://92.5.55.85:27778/users/login";

    // Login has no side effects, so it's safe to auto-retry transient/truncated responses
    // (the backend intermittently drops the tail of a response -> "unexpected end of stream").
    private static final int LOGIN_MAX_ATTEMPTS = 3;

    private EditText edtUsername, edtPassword;
    private Button btnLogin;
    /** Re-opens the biometric prompt after a dismissal; only shown for a locked saved session. */
    private Button btnUnlock;
    private ProgressBar progressLogin;

    private OkHttpClient client;

    /**
     * Registration normally signs the user in itself and never comes back. It only returns
     * {@code RESULT_OK} in the fallback case where the account was created but the server issued no
     * token — then we pre-fill the username so the user only has to retype the password.
     */
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

        // Auto-skip login if already signed in and the session isn't locked. Returning here (the
        // finish() used to fall through into the rest of onCreate) keeps the login form from being
        // inflated for a split second on the way to MainActivity.
        if (hasSession && !BiometricLock.isEnabled(this)) {
            goToMain();
            return;
        }

        setContentView(R.layout.activity_login);

        // Applied to the ScrollView, not the root, so the background photo stays full-bleed.
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
            // A locked session. The password form stays available underneath as the way in when
            // biometrics are refused or have been removed from the device — the saved session is
            // simply not honoured until the owner proves who they are.
            btnUnlock.setVisibility(View.VISIBLE);
            promptUnlock();
        }
    }

    /**
     * Asks for biometrics to release the saved session. Unavailable hardware (or no enrolment any
     * more) is treated as "can't verify" and leaves the user on the password form rather than
     * either locking them out or silently waving them through.
     */
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
                // else: the user dismissed it deliberately — the password form is already on screen.
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

            // Prevent a second tap from firing another request while this one is in flight.
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
        Request request = new Request.Builder().url(API_URL).post(body).build();

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
                boolean networkError = false;   // true = truncated/unreadable body (retriable)
                JSONObject data = null;
                try (Response r = response) {
                    String responseBody = r.body() != null ? r.body().string() : "";
                    if (r.isSuccessful()) {
                        data = new JSONObject(responseBody);
                        success = true;
                    }
                    // Non-2xx = genuine rejection (e.g. bad credentials): not retriable.
                } catch (IOException e) {
                    networkError = true;         // e.g. "unexpected end of stream" — worth retrying
                } catch (JSONException e) {
                    // Server sent 2xx but unparseable body; treat as a failed attempt (not auth).
                    networkError = true;
                }

                if (success) {
                    // Save logged user + profile info (SharedPreferences = Android localStorage).
                    // opt* is used so a missing field falls back to a default instead of throwing.
                    SharedPreferences prefs =
                            getSharedPreferences("auth", MODE_PRIVATE);

                    prefs.edit()
                            // Bearer token authorizes every subsequent request (see ApiClient).
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

                // Retry only transient/truncation errors, not a real credential rejection.
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