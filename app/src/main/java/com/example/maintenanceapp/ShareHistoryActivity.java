package com.example.maintenanceapp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.maintenanceapp.model.Vehicle;
import com.example.maintenanceapp.util.ApiClient;
import com.example.maintenanceapp.util.QrCodes;
import com.example.maintenanceapp.util.ScreenInsets;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

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

/**
 * Shows a scannable link to one vehicle's public service history: the buyer points a camera at the
 * QR and reads the record in a browser, with no app to install.
 *
 * <p>The link is minted server-side ({@code POST /vehicles/share}) rather than built here, because
 * the token has to be unguessable and revocable — a client-side URL containing the vehicle id would
 * let anyone enumerate every car in the database.
 */
public class ShareHistoryActivity extends AppCompatActivity {

    public static final String EXTRA_VEHICLE = "extra_vehicle";

    private static final String SHARE_URL = "http://92.5.55.85:27778/vehicles/share";
    private static final String REVOKE_URL = "http://92.5.55.85:27778/vehicles/share/revoke";
    private static final int QR_PX = 640;

    private OkHttpClient client;
    private Vehicle vehicle;

    private ProgressBar progress;
    private View content;
    private ImageView imgQr;
    private TextView txtLink, txtExpiry;

    private String shareUrl;
    private boolean revokeInFlight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share_history);

        client = ApiClient.get(this);
        vehicle = (Vehicle) getIntent().getSerializableExtra(EXTRA_VEHICLE);
        if (vehicle == null || vehicle.id == null || vehicle.id.isEmpty()) {
            Toast.makeText(this, R.string.share_error, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        ScreenInsets.apply(findViewById(R.id.shRoot));

        progress = findViewById(R.id.shProgress);
        content = findViewById(R.id.shContent);
        imgQr = findViewById(R.id.imgQr);
        txtLink = findViewById(R.id.txtLink);
        txtExpiry = findViewById(R.id.txtExpiry);

        ((ImageButton) findViewById(R.id.btnBack)).setOnClickListener(v -> finish());
        ((MaterialButton) findViewById(R.id.btnCopyLink)).setOnClickListener(v -> copyLink());
        ((MaterialButton) findViewById(R.id.btnSendLink)).setOnClickListener(v -> sendLink());
        ((MaterialButton) findViewById(R.id.btnRevoke)).setOnClickListener(v -> confirmRevoke());

        requestLink();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A dim screen is the most common reason a QR won't scan, and the user can't fix that
        // without leaving this screen. Restored automatically when the Activity goes away.
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.screenBrightness = 1.0f;
        getWindow().setAttributes(attrs);
    }

    /** Asks the server for this vehicle's share link (creating one if there isn't a live one). */
    private void requestLink() {
        String json;
        try {
            json = new JSONObject().put("vehicleId", vehicle.id).toString();
        } catch (JSONException e) {
            showError();
            return;
        }
        Request request = new Request.Builder()
                .url(SHARE_URL)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Share", "POST /vehicles/share failed", e);
                runOnUiThread(ShareHistoryActivity.this::showError);
            }

            @Override
            public void onResponse(Call call, Response response) {
                String url = null;
                String expiresAt = null;
                try (Response r = response) {
                    if (r.isSuccessful() && r.body() != null) {
                        JSONObject o = new JSONObject(r.body().string());
                        url = o.optString("url", "");
                        expiresAt = o.optString("expiresAt", "");
                    } else {
                        Log.e("Share", "POST /vehicles/share -> HTTP " + r.code());
                    }
                } catch (IOException | JSONException e) {
                    Log.e("Share", "share response read/parse failed", e);
                }

                final String finalUrl = url;
                final String finalExpiry = expiresAt;
                runOnUiThread(() -> {
                    if (finalUrl == null || finalUrl.isEmpty()) {
                        showError();
                    } else {
                        showLink(finalUrl, finalExpiry);
                    }
                });
            }
        });
    }

    private void showLink(String url, String expiresAt) {
        shareUrl = url;
        // Encoding is a pure computation on a short string — fast enough not to warrant a thread,
        // and doing it here keeps the bitmap's lifetime tied to the view that shows it.
        Bitmap qr = QrCodes.encode(url, QR_PX);
        if (qr == null) {
            showError();
            return;
        }
        imgQr.setImageBitmap(qr);
        txtLink.setText(url);
        txtExpiry.setText(expiresAt == null || expiresAt.isEmpty()
                ? getString(R.string.share_no_expiry)
                : getString(R.string.share_expires, expiresAt));

        progress.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);
    }

    private void showError() {
        progress.setVisibility(View.GONE);
        Toast.makeText(this, R.string.share_error, Toast.LENGTH_LONG).show();
        finish();
    }

    private void copyLink() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.share_title), shareUrl));
        Toast.makeText(this, R.string.share_copied, Toast.LENGTH_SHORT).show();
    }

    private void sendLink() {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, shareUrl);
        startActivity(Intent.createChooser(send, getString(R.string.share_title)));
    }

    /** Revoking kills the link for everyone who already has it, so it's worth a confirm. */
    private void confirmRevoke() {
        if (revokeInFlight) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.share_revoke_title)
                .setMessage(R.string.share_revoke_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.share_revoke, (dialog, which) -> revoke())
                .show();
    }

    private void revoke() {
        String json;
        try {
            json = new JSONObject().put("vehicleId", vehicle.id).toString();
        } catch (JSONException e) {
            Toast.makeText(this, R.string.share_revoke_error, Toast.LENGTH_LONG).show();
            return;
        }
        revokeInFlight = true;
        findViewById(R.id.btnRevoke).setEnabled(false);

        Request request = new Request.Builder()
                .url(REVOKE_URL)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Share", "POST /vehicles/share/revoke failed", e);
                runOnUiThread(() -> revokeFailed());
            }

            @Override
            public void onResponse(Call call, Response response) {
                boolean ok;
                try (Response r = response) {
                    ok = r.isSuccessful();
                }
                runOnUiThread(() -> {
                    if (!ok) {
                        revokeFailed();
                        return;
                    }
                    Toast.makeText(ShareHistoryActivity.this, R.string.share_revoked,
                            Toast.LENGTH_SHORT).show();
                    finish();   // the link on screen is dead; don't leave it scannable
                });
            }
        });
    }

    private void revokeFailed() {
        revokeInFlight = false;
        findViewById(R.id.btnRevoke).setEnabled(true);
        Toast.makeText(this, R.string.share_revoke_error, Toast.LENGTH_LONG).show();
    }
}
