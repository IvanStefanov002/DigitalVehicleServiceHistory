package com.example.maintenanceapp;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
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
import androidx.appcompat.app.AppCompatActivity;

import com.example.maintenanceapp.util.ScreenInsets;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Assisted document check: hosts an official captcha-gated check page in a WebView so the user solves
 * the challenge themselves, then reads the resulting date out of the rendered page and offers it back.
 *
 * <p>Site-agnostic on purpose — the caller passes the URL, the host to inject on, the value to
 * prefill/copy, the CSS selectors for the field, and the screen's labels. <b>Only the ГТП check
 * (RTA, an Angular SPA with a Google reCAPTCHA) uses it.</b> ГО used to as well, but the Guarantee
 * Fund's page loaded only intermittently in a WebView and now opens in the browser instead — see
 * {@code VehicleComplianceActivity.openInsuranceCheck()} before reusing this for another site, and
 * assume nothing about a page working here just because RTA's does.
 *
 * <p><b>Why a WebView and not a background fetch.</b> The page sits behind a captcha, and the only
 * honest way past a captcha is a human. RTA's own page makes the request with a real, user-solved
 * challenge; this screen never sees, forges, or reuses that token. It merely reads the date the page
 * has already rendered.
 *
 * <p><b>Nothing is saved silently.</b> Scraping a third party's DOM is brittle, so a detected date is
 * only <em>offered</em> — the user confirms it against what is on screen, and the caller persists it.
 * If the DOM changes and nothing is found, the result bar never appears and the user falls back to
 * reading the date and using "+1 година" or the picker. Failure mode is "no help", never "wrong date".
 *
 * <p>Returns {@link #EXTRA_RESULT_DATE} ({@code yyyy-MM-dd}) with {@code RESULT_OK} on confirmation.
 */
public class VinCheckActivity extends AppCompatActivity {

    private static final String TAG = "VinCheck";

    public static final String EXTRA_URL = "extra_url";                 // the check page
    public static final String EXTRA_ALLOWED_HOST = "extra_allowed_host"; // host to inject on
    public static final String EXTRA_FILL_VALUE = "extra_fill_value";   // VIN or plate to prefill/copy
    public static final String EXTRA_FILL_SELECTORS = "extra_fill_selectors"; // String[] of CSS selectors
    public static final String EXTRA_TITLE = "extra_title";             // screen title (resolved)
    public static final String EXTRA_HINT = "extra_hint";               // hint strip text (resolved)
    public static final String EXTRA_RESULT_DATE = "extra_result_date"; // output, yyyy-MM-dd

    /** A load that hasn't finished by now gets the retry bar. Not a cancel — the load continues. */
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

        // chrome://inspect on a debug build. This whole class of bug ("sometimes the page just doesn't
        // come up") is only diagnosable with the page's own console and network log.
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        // Put the value on the clipboard up front: the JS prefill is best-effort (a site's field ids
        // can change), and a paste is the reliable fallback the user always has.
        if (fillValue != null && !fillValue.trim().isEmpty()) {
            String v = fillValue.trim();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(v, v));
            }
        }

        // The layout's title is static ("Проверка на документ" isn't specific enough), so the caller
        // passes a resolved title/hint per document. Title lands on #vcTitle, hint on #vcHint.
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
        // Paint white immediately. The WebView's surface is black until the page first paints, and on
        // a heavy server-rendered page (the Guarantee Fund) that gap reads as a "black screen"; a
        // white background bridges it and matches every real page we load.
        web.setBackgroundColor(android.graphics.Color.WHITE);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);       // SPA + ALTCHA both need JS; nothing renders without it
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        // Some CMS pages pull a subresource over http on an https page; the default WebView blocks
        // that outright, which can leave the page half-rendered. Compatibility mode allows it for
        // images/styles while still blocking active mixed content.
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // The "; wv" UA strip is ONLY for Google reCAPTCHA (RTA), which refuses to run under the
        // WebView token. The host guard stays even though RTA is the only caller left: the strip once
        // gave the Guarantee Fund a black screen (an unusual UA can trip a CMS/WAF), so it must never
        // be applied blindly to whatever page is wired up next.
        if (allowedHost.contains("rta.government.bg")) {
            ws.setUserAgentString(ws.getUserAgentString().replace("; wv", ""));
        }

        // A WebChromeClient makes the WebView a "full" browser environment — some pages don't lay out
        // correctly without one, and it drives the progress bar.
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
            }

            /** The page's own JS errors, in logcat. This is how the ALTCHA init race was identified. */
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

            /**
             * A failed main-frame load used to be indistinguishable from a slow one: both were a blank
             * WebView with no way out but Back. Subframe errors are ignored — a dead tracker iframe is
             * not a failed page.
             */
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
                // Only ever inject on the caller's own origin — never a captcha frame or a redirect
                // target. The Fund's POST reloads the page, so this re-runs on the result page too.
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

    /**
     * Reloads the original URL rather than calling {@link WebView#reload()}: the Fund's search is a
     * POST, so a reload from a result page would re-submit it, where what the user wants after a
     * failed load is the clean form back.
     */
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

    // ---- JS bridge -----------------------------------------------------------

    private final class Bridge {
        /**
         * Called from the injected script with a JSON array of {@code {date, label}} scraped from the
         * rendered result. Runs on a binder thread → hops to the UI thread. Untrusted by construction
         * (any frame's JS could call it), which is exactly why the result is confirmed, never saved.
         */
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

    /**
     * Parses the scraped list into confirmed candidates. Only strings that parse as {@code dd.MM.yyyy}
     * are kept; candidates whose label hints at an expiry ("валид", "до", "след", "преглед") float to
     * the top so the pre-selected choice is the useful one.
     */
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

    /** One candidate → return immediately; several → let the user pick, pre-selecting the first. */
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
     * The script injected into the check page. It (1) prefills the field found by {@code selectorsJson}
     * with {@code fillValue}, retrying because a field may mount asynchronously (Angular) or after a
     * reload (the Fund's POST), and (2) watches the DOM and reports any {@code dd.MM.yyyy} dates it
     * finds in small (leaf-ish) elements, with the surrounding text as a label. Binds once per page
     * and never throws into the host page.
     *
     * <p>{@code selectorsJson} is a JSON array, which is also a valid JS array literal, so it drops
     * straight in.
     */
    private static String injectedScript(@Nullable String value, String selectorsJson) {
        String safe = value == null ? "" : value.trim().replace("\\", "\\\\").replace("'", "\\'");
        return "(function(){"
                + "if(window.__mvBound)return;window.__mvBound=true;"
                + "var VAL='" + safe + "';var SELS=" + selectorsJson + ";"
                + "function setVal(el,val){try{var p=Object.getPrototypeOf(el);"
                + "var d=Object.getOwnPropertyDescriptor(p,'value');"
                + "if(d&&d.set){d.set.call(el,val);}else{el.value=val;}"
                + "['input','change','keyup','blur'].forEach(function(t){"
                + "el.dispatchEvent(new Event(t,{bubbles:true}));});}catch(e){}}"
                + "function findField(){for(var i=0;i<SELS.length;i++){"
                + "var el=document.querySelector(SELS[i]);if(el)return el;}"
                + "var ins=document.querySelectorAll('input[type=\"text\"],input:not([type])');"
                + "for(var j=0;j<ins.length;j++){if(ins[j].offsetParent!==null)return ins[j];}return null;}"
                + "var n=0;var t=setInterval(function(){n++;var el=findField();"
                + "if(el&&VAL){setVal(el,VAL);clearInterval(t);}if(n>40)clearInterval(t);},250);"
                + "var re=/\\b\\d{2}\\.\\d{2}\\.\\d{4}\\b/;var last='';"
                + "function scan(){try{"
                + "var els=document.querySelectorAll('td,th,li,p,span,div,strong,b,label');"
                + "var out=[],seen={};"
                + "for(var i=0;i<els.length;i++){var el=els[i];var tx=(el.textContent||'').trim();"
                + "if(!tx||tx.length>120)continue;var m=tx.match(re);if(!m)continue;"
                + "var lab=tx.replace(m[0],'').replace(/[\\s:\\-–]+$/,'').trim().slice(0,60);"
                + "var k=m[0]+'|'+lab;if(seen[k])continue;seen[k]=1;out.push({date:m[0],label:lab});}"
                + "if(out.length){var pl=JSON.stringify(out);if(pl!==last){last=pl;"
                + "if(window.MvBridge&&MvBridge.onDatesFound)MvBridge.onDatesFound(pl);}}}catch(e){}}"
                + "try{var mo=new MutationObserver(function(){scan();});"
                + "mo.observe(document.body,{childList:true,subtree:true,characterData:true});}catch(e){}"
                + "scan();"
                + "})();";
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
