/*
 * VinCheckActivity.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

package com.example.maintenanceapp;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;
import androidx.appcompat.app.AppCompatActivity;

import com.example.maintenanceapp.util.ScreenInsets;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class VinCheckActivity extends AppCompatActivity {

    private static final String TAG = "VinCheck";
    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_ALLOWED_HOST = "extra_allowed_host";
    public static final String EXTRA_FILL_VALUE = "extra_fill_value";
    public static final String EXTRA_FILL_SELECTORS = "extra_fill_selectors";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_HINT = "extra_hint";
    public static final String EXTRA_RESULT_DATE = "extra_result_date";
    private static final long LOAD_TIMEOUT_MS = 20_000L;

    private WebView web;
    private View resultBar;
    private TextView resultText;
    private View errorBar;

    private String url;
    private String allowedHost;
    private String fillValue;
    private String selectorsJson = "[]";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable loadWatchdog = this::showErrorBar;

    /** Detected date candidates; expiry-like ones first. */
    private final List<Candidate> candidates = new ArrayList<>();

    private static final class Candidate {
        final String iso;      // yyyy-MM-dd
        final String display;  // "<label> — dd.MM.yyyy"
        Candidate(String iso, String display) { this.iso = iso; this.display = display; }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vin_check);

        ScreenInsets.apply(findViewById(R.id.vcRoot));

        url = getIntent().getStringExtra(EXTRA_URL);
        allowedHost = getIntent().getStringExtra(EXTRA_ALLOWED_HOST);
        fillValue = getIntent().getStringExtra(EXTRA_FILL_VALUE);
        String[] selectors = getIntent().getStringArrayExtra(EXTRA_FILL_SELECTORS);
        if (selectors != null) {
            selectorsJson = new JSONArray(Arrays.asList(selectors)).toString();
        }
        if (url == null || url.isEmpty() || allowedHost == null || allowedHost.isEmpty()) {
            finish();
            return;
        }

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        if (fillValue != null && !fillValue.trim().isEmpty()) {
            String v = fillValue.trim();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(v, v));
            }
        }

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null) {
            ((TextView) findViewById(R.id.vcTitle)).setText(title);
        }
        String hintText = getIntent().getStringExtra(EXTRA_HINT);
        ((TextView) findViewById(R.id.vcHint))
                .setText(hintText != null ? hintText : getString(R.string.vc_hint));

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnOpenBrowser).setOnClickListener(v -> openInBrowser());
        findViewById(R.id.btnReload).setOnClickListener(v -> reload());
        resultBar = findViewById(R.id.vcResultBar);
        resultText = findViewById(R.id.vcResultText);
        findViewById(R.id.vcBtnUse).setOnClickListener(v -> chooseAndReturn());
        errorBar = findViewById(R.id.vcErrorBar);
        findViewById(R.id.vcBtnReload).setOnClickListener(v -> reload());
        findViewById(R.id.vcBtnBrowser).setOnClickListener(v -> openInBrowser());

        ProgressBar progress = findViewById(R.id.vcProgress);

        web = findViewById(R.id.vcWeb);
        web.setBackgroundColor(android.graphics.Color.WHITE);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        if (allowedHost.contains("rta.government.bg")) {
            ws.setUserAgentString(ws.getUserAgentString().replace("; wv", ""));
        }

        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, "console: " + m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "MvBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if (scheme != null && (scheme.equals("http") || scheme.equals("https"))) {
                    return false;   // keep http(s) in the WebView so the page + its captcha frames work
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException e) {
                    Log.w(TAG, "no handler for " + u, e);
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    Log.w(TAG, "main-frame load error " + error.getErrorCode() + " "
                            + error.getDescription() + " for " + request.getUrl());
                    showErrorBar();
                }
            }

            @Override
            public void onPageStarted(WebView view, String u, android.graphics.Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
                hideErrorBar();
                ui.removeCallbacks(loadWatchdog);
                ui.postDelayed(loadWatchdog, LOAD_TIMEOUT_MS);
            }

            @Override
            public void onPageFinished(WebView view, String u) {
                progress.setVisibility(View.GONE);
                ui.removeCallbacks(loadWatchdog);
                hideErrorBar();
                if (isAllowed(u)) {
                    view.evaluateJavascript(injectedScript(fillValue, selectorsJson), null);
                }
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack();
                } else {
                    finish();
                }
            }
        });

        web.loadUrl(url);
    }

    private boolean isAllowed(String u) {
        Uri uri = Uri.parse(u == null ? "" : u);
        return allowedHost.equalsIgnoreCase(uri.getHost());
    }

    private void showErrorBar() {
        if (errorBar != null) {
            errorBar.setVisibility(View.VISIBLE);
        }
    }

    private void hideErrorBar() {
        if (errorBar != null) {
            errorBar.setVisibility(View.GONE);
        }
    }

    private void reload() {
        hideErrorBar();
        candidates.clear();
        resultBar.setVisibility(View.GONE);
        web.loadUrl(url);
    }

    private void openInBrowser() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.cmp_no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    private final class Bridge {
        @JavascriptInterface
        public void onDatesFound(String json) {
            final List<Candidate> parsed = parseCandidates(json);
            if (parsed.isEmpty()) {
                return;
            }
            runOnUiThread(() -> {
                candidates.clear();
                candidates.addAll(parsed);
                resultText.setText(candidates.size() == 1
                        ? getString(R.string.vc_result_one, candidates.get(0).display)
                        : getString(R.string.vc_result_found));
                resultBar.setVisibility(View.VISIBLE);
            });
        }
    }

    private List<Candidate> parseCandidates(String json) {
        List<Candidate> primary = new ArrayList<>();
        List<Candidate> secondary = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        SimpleDateFormat in = new SimpleDateFormat("dd.MM.yyyy", Locale.US);
        in.setLenient(false);

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String raw = o.optString("date", "").trim();
                String label = o.optString("label", "").trim();
                Date d;
                try {
                    d = in.parse(raw);
                } catch (ParseException e) {
                    continue;
                }
                if (d == null) {
                    continue;
                }
                String iso = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d);
                String key = iso + "|" + label;
                if (!seen.add(key)) {
                    continue;
                }
                String display = label.isEmpty() ? raw : label + " — " + raw;
                Candidate c = new Candidate(iso, display);
                String low = label.toLowerCase(new Locale("bg"));
                if (low.contains("валид") || low.contains("до") || low.contains("след")
                        || low.contains("преглед")) {
                    primary.add(c);
                } else {
                    secondary.add(c);
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "bad candidate JSON", e);
        }
        primary.addAll(secondary);
        return primary;
    }

    private void chooseAndReturn() {
        if (candidates.isEmpty()) {
            return;
        }
        if (candidates.size() == 1) {
            returnDate(candidates.get(0).iso);
            return;
        }
        String[] items = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            items[i] = candidates.get(i).display;
        }
        final int[] picked = {0};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vc_pick_date)
                .setSingleChoiceItems(items, 0, (d, which) -> picked[0] = which)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.vc_use_date,
                        (d, which) -> returnDate(candidates.get(picked[0]).iso))
                .show();
    }

    private void returnDate(String iso) {
        Intent data = new Intent();
        data.putExtra(EXTRA_RESULT_DATE, iso);
        setResult(RESULT_OK, data);
        finish();
    }

    /**
     * The page script, read from {@code res/raw/vin_check.js} with its two placeholders filled in.
     *
     * <p>It lives in a real .js file rather than a Java string so the regexes aren't double-escaped
     * and an editor can highlight it — this is the most brittle code in the app and it has to stay
     * readable. Read the header comment in that file before changing any of it.
     *
     * <p>Returns an empty string if the resource can't be read, which evaluates to a no-op: the
     * user then fills the field by hand, which is the same fallback every other failure here has.
     */
    private String injectedScript(@Nullable String value, String selectorsJson) {
        String js = readRaw(R.raw.vin_check);
        if (js.isEmpty()) {
            return "";
        }
        // JSONObject.quote emits the surrounding quotes and escapes the contents, so the VIN can't
        // break out of its literal no matter what the caller passed.
        String literal = JSONObject.quote(value == null ? "" : value.trim());
        return js.replace("__MV_VALUE__", literal)
                .replace("__MV_SELECTORS__", selectorsJson);
    }

    private String readRaw(@RawRes int res) {
        try (InputStream in = getResources().openRawResource(res)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException | Resources.NotFoundException e) {
            Log.w(TAG, "could not read injected script", e);
            return "";
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(loadWatchdog);
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }
}
