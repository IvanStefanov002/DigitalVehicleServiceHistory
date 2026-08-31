/*
 * RegisterActivity.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

package com.example.maintenanceapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Patterns;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.maintenanceapp.util.Api;
import com.example.maintenanceapp.util.ApiClient;
import com.example.maintenanceapp.util.ScreenInsets;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;

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

public class RegisterActivity extends AppCompatActivity {

    public static final String EXTRA_USERNAME = "extra_username";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MIN_USERNAME_LENGTH = 3;
    private static final String USERNAME_PATTERN = "[A-Za-z0-9._-]+";

    private TextInputLayout tilFullName, tilUsername, tilEmail, tilPassword, tilConfirm;
    private EditText edtFullName, edtUsername, edtEmail, edtPassword, edtConfirm;
    private MaterialButton btnRegister;
    private ProgressBar progressRegister;

    private OkHttpClient client;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        client = ApiClient.get(this);
        ScreenInsets.apply(findViewById(R.id.regContent));

        tilFullName = findViewById(R.id.tilFullName);
        tilUsername = findViewById(R.id.tilUsername);
        tilEmail = findViewById(R.id.tilEmail);
        tilPassword = findViewById(R.id.tilPassword);
        tilConfirm = findViewById(R.id.tilConfirm);

        edtFullName = findViewById(R.id.edtFullName);
        edtUsername = findViewById(R.id.edtUsername);
        edtEmail = findViewById(R.id.edtEmail);
        edtPassword = findViewById(R.id.edtPassword);
        edtConfirm = findViewById(R.id.edtConfirm);

        btnRegister = findViewById(R.id.btnRegister);
        progressRegister = findViewById(R.id.progressRegister);

        ImageButton btnBack = findViewById(R.id.btnBack);
        MaterialButton btnGoToLogin = findViewById(R.id.btnGoToLogin);

        btnBack.setOnClickListener(v -> finish());
        btnGoToLogin.setOnClickListener(v -> finish());
        btnRegister.setOnClickListener(v -> register());

        clearErrorOnType(tilFullName, edtFullName);
        clearErrorOnType(tilUsername, edtUsername);
        clearErrorOnType(tilEmail, edtEmail);
        clearErrorOnType(tilPassword, edtPassword);
        clearErrorOnType(tilConfirm, edtConfirm);
    }

    // ---------------------------------------------------------------- validation

    /** Validates every field and marks all offenders at once, then focuses the first one. */
    private boolean validate() {
        String fullName = text(edtFullName);
        String username = text(edtUsername);
        String email = text(edtEmail);
        String password = text(edtPassword);
        String confirm = text(edtConfirm);

        TextInputLayout firstBad = null;

        if (fullName.isEmpty()) {
            firstBad = fail(tilFullName, getString(R.string.reg_err_required), firstBad);
        }

        if (username.isEmpty()) {
            firstBad = fail(tilUsername, getString(R.string.reg_err_required), firstBad);
        } else if (username.length() < MIN_USERNAME_LENGTH) {
            firstBad = fail(tilUsername, getString(R.string.reg_err_username_short), firstBad);
        } else if (!username.matches(USERNAME_PATTERN)) {
            firstBad = fail(tilUsername, getString(R.string.reg_err_username_chars), firstBad);
        }

        if (email.isEmpty()) {
            firstBad = fail(tilEmail, getString(R.string.reg_err_required), firstBad);
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            firstBad = fail(tilEmail, getString(R.string.reg_err_email), firstBad);
        }

        if (password.isEmpty()) {
            firstBad = fail(tilPassword, getString(R.string.reg_err_required), firstBad);
        } else if (password.length() < MIN_PASSWORD_LENGTH) {
            firstBad = fail(tilPassword, getString(R.string.reg_err_password_short), firstBad);
        }

        if (confirm.isEmpty()) {
            firstBad = fail(tilConfirm, getString(R.string.reg_err_required), firstBad);
        } else if (tilPassword.getError() == null && !confirm.equals(password)) {
            firstBad = fail(tilConfirm, getString(R.string.reg_err_confirm), firstBad);
        }

        if (firstBad != null) {
            firstBad.requestFocus();
            return false;
        }
        return true;
    }

    private TextInputLayout fail(TextInputLayout til, String message, TextInputLayout firstBad) {
        til.setError(message);
        return firstBad == null ? til : firstBad;
    }

    private void clearErrorOnType(TextInputLayout til, EditText edt) {
        edt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                til.setError(null);
            }
        });
    }

    private void register() {
        if (!validate()) {
            return;
        }

        final String username = text(edtUsername);
        JSONObject json = new JSONObject();
        try {
            json.put("username", username);
            json.put("password", text(edtPassword));
            json.put("fullName", text(edtFullName));
            json.put("email", text(edtEmail).toLowerCase());
        } catch (JSONException e) {
            Toast.makeText(this, R.string.reg_error, Toast.LENGTH_SHORT).show();
            return;
        }

        setBusy(true);
        Request request = new Request.Builder()
                .url(Api.REGISTER)
                .post(RequestBody.create(json.toString(), JSON))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    Toast.makeText(RegisterActivity.this,
                            R.string.reg_network_error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) {
                int code;
                String bodyText = "";
                try (Response r = response) {
                    code = r.code();
                    if (r.body() != null) {
                        bodyText = r.body().string();
                    }
                } catch (IOException e) {
                    runOnUiThread(() -> {
                        setBusy(false);
                        Toast.makeText(RegisterActivity.this,
                                R.string.reg_network_error, Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                JSONObject data = null;
                try {
                    if (!bodyText.isEmpty()) {
                        data = new JSONObject(bodyText);
                    }
                } catch (JSONException ignored) {
                    // Leave data null; handled per branch below.
                }

                final int status = code;
                final JSONObject payload = data;
                runOnUiThread(() -> {
                    if (status / 100 == 2) {
                        onRegistered(username, payload);
                    } else {
                        setBusy(false);
                        showRejection(status, payload);
                    }
                });
            }
        });
    }

    /** 2xx: either sign the user in with the returned token, or hand them back to login. */
    private void onRegistered(String username, JSONObject data) {
        String token = data == null ? "" : data.optString("token", "");

        if (token.isEmpty()) {
            // Account created but unauthenticated — see class javadoc.
            Toast.makeText(this, R.string.reg_created_please_login, Toast.LENGTH_LONG).show();
            Intent result = new Intent();
            result.putExtra(EXTRA_USERNAME, username);
            setResult(RESULT_OK, result);
            finish();
            return;
        }

        // Same keys, same order as LoginActivity — the rest of the app reads this file directly.
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        prefs.edit()
                .putString("token", token)
                .putString("username", data.optString("username", username))
                .putString("fullName", data.optString("fullName", text(edtFullName)))
                .putString("email", data.optString("email", text(edtEmail).toLowerCase()))
                .putInt("vehicleCount", data.optInt("vehicleCount", 0))
                .putString("profileImageBase64", data.optString("profileImageBase64", ""))
                .apply();

        Toast.makeText(this,
                getString(R.string.reg_welcome, prefs.getString("fullName", username)),
                Toast.LENGTH_SHORT).show();

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    /** Non-2xx. A duplicate is pinned to the field that caused it */
    private void showRejection(int status, JSONObject data) {
        String error = data == null ? "" : data.optString("error", "");

        if (error.contains("username")) {
            tilUsername.setError(getString(R.string.reg_taken_username));
            tilUsername.requestFocus();
            return;
        }
        if (error.contains("email")) {
            tilEmail.setError(getString(R.string.reg_taken_email));
            tilEmail.requestFocus();
            return;
        }

        // 409 is the contract; 460 is this backend's habit for "refused, and it's your data's fault".
        if (status == 409 || status == 460) {
            tilUsername.setError(getString(R.string.reg_taken_username));
            tilEmail.setError(getString(R.string.reg_taken_email));
            tilUsername.requestFocus();
            return;
        }

        Toast.makeText(this, R.string.reg_error, Toast.LENGTH_LONG).show();
    }

    private void setBusy(boolean busy) {
        btnRegister.setEnabled(!busy);
        progressRegister.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private String text(EditText edt) {
        return edt.getText().toString().trim();
    }
}
