package com.example.maintenanceapp;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.maintenanceapp.model.Vehicle;
import com.example.maintenanceapp.model.VignetteInfo;
import com.example.maintenanceapp.util.ApiClient;
import com.example.maintenanceapp.util.ComplianceStatus;
import com.example.maintenanceapp.util.ScreenInsets;
import com.example.maintenanceapp.util.SwipeRefresh;
import com.example.maintenanceapp.util.VehicleType;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONException;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * One vehicle's time-limited documents: e-vignette, periodic technical inspection (ГТП) and
 * third-party liability insurance (ГО).
 *
 * <p><b>The screen deliberately mixes two tiers of trust, and says so.</b> The vignette comes from
 * our backend, which proxies the National Toll Administration's public plate check — an
 * authoritative answer. ГТП and ГО cannot be fetched at all: the АА and Guarantee Fund check pages
 * are both behind a server-enforced CAPTCHA, so those two are dates the user typed in, and the app
 * must never dress them up as verified. Every card carries its provenance in {@code cmpSource} and
 * the footnote spells the difference out. If either authority ever offers a sanctioned feed,
 * promoting that card to tier 1 is a source string plus a fetch — nothing else here moves.
 *
 * <p>Returns {@code RESULT_OK} with {@link #EXTRA_RESULT_VEHICLE} whenever a declared date was
 * saved, so the detail screen re-binds and {@code MainActivity} reloads its cache.
 */
public class VehicleComplianceActivity extends AppCompatActivity {

    public static final String EXTRA_VEHICLE = "extra_vehicle";               // input
    public static final String EXTRA_RESULT_VEHICLE = "extra_result_vehicle"; // output on RESULT_OK

    private static final String VIGNETTE_URL = "http://92.5.55.85:27778/vehicles/vignette";
    private static final String UPDATE_URL = "http://92.5.55.85:27778/vehicles/update";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** Same truncation workaround as the other idempotent GETs — see the retry note in CLAUDE.md. */
    private static final int VIGNETTE_MAX_ATTEMPTS = 3;

    /** The toll authority's own sales portal — the only place a vignette can actually be bought. */
    private static final String URL_BUY_VIGNETTE = "https://web.bgtoll.bg/";

    /**
     * The toll authority's own e-vignette check — the human-facing front end over the very API our
     * backend proxies, so it can only ever agree with us. Shown when our check came back
     * <em>unknown</em>, which is otherwise a dead end for the user.
     *
     * <p><b>Note the {@code #/} — it is a hash router</b> and plain {@code /vignette} 404s.
     *
     * <p><b>Not {@code web.bgtoll.bg/TollProduct}.</b> That is the purchase checkout, not a check:
     * ASP.NET with an {@code __RequestVerificationToken} <em>and</em> reCAPTCHA. It is the one page
     * here that is captcha-gated, i.e. the one that would repeat the ГО WebView saga.
     *
     * <p><b>Browser, not {@link VinCheckActivity}.</b> Hosting a page in-app is only justified by a
     * captcha needing a human; this page has none, and its bundle reads no query params, so a WebView
     * would mean injecting JS into another React SPA for nothing. The plate goes on the clipboard
     * instead, exactly like the buy link.
     */
    private static final String URL_CHECK_VIGNETTE = "https://check.bgtoll.bg/#/vignette";

    /**
     * АА's public ГТП check. Captcha-gated, hence a link out rather than a fetch.
     *
     * <p>Points at the newer EIS <b>VIN-check</b> page, not the old plate page: it is keyed on the
     * VIN we already store, so the assist is copy-VIN-then-paste rather than making the user find
     * and transcribe anything. (RTA does run a JSON API behind this page, but it sits behind a
     * reCAPTCHA — a link-out with the VIN on the clipboard is the honest use until we have
     * authorised access; see docs/rta-access-request-email.md.)
     */
    private static final String URL_CHECK_INSPECTION =
            "https://public-eis.rta.government.bg/public-vehicle-check/vin-check";

    /**
     * Host we inject the assisted-check script into — must match the check page's own host. Only ГТП
     * has one: ГО is a plain link-out (see {@link #openInsuranceCheck()}).
     */
    private static final String HOST_INSPECTION = "public-eis.rta.government.bg";

    /**
     * The Guarantee Fund's public ГО check, opened in the <b>browser</b>, not a WebView. Percent-encoded
     * Cyrillic path, kept verbatim — the site redirects to its home page if any of it is re-encoded.
     */
    private static final String URL_CHECK_INSURANCE =
            "https://www.guaranteefund.org/bg/%D0%B8%D0%BD%D1%84%D0%BE%D1%80%D0%BC%D0%B0%D1%86%D0%B8%D0%BE%D0%BD%D0%B5%D0%BD-%D1%86%D0%B5%D0%BD%D1%82%D1%8A%D1%80-%D0%B8-%D1%81%D0%BF%D1%80%D0%B0%D0%B2%D0%BA%D0%B8/%D1%83%D1%81%D0%BB%D1%83%D0%B3%D0%B8/%D0%BF%D1%80%D0%BE%D0%B2%D0%B5%D1%80%D0%BA%D0%B0-%D0%B7%D0%B0-%D0%B2%D0%B0%D0%BB%D0%B8%D0%B4%D0%BD%D0%B0-%D0%B7%D0%B0%D1%81%D1%82%D1%80%D0%B0%D1%85%D0%BE%D0%B2%D0%BA%D0%B0-%D0%B3%D1%80a%D0%B6%D0%B4a%D0%BD%D1%81%D0%BAa-%D0%BE%D1%82%D0%B3%D0%BE%D0%B2%D0%BE%D1%80%D0%BD%D0%BE%D1%81%D1%82-%D0%BD%D0%B0-%D0%B0%D0%B2%D1%82%D0%BE%D0%BC%D0%BE%D0%B1%D0%B8%D0%BB%D0%B8%D1%81%D1%82%D0%B8%D1%82%D0%B5";

    private OkHttpClient client;
    private Vehicle vehicle;
    private SwipeRefreshLayout swipe;

    /**
     * The last vignette answer, or {@code null} when we don't have one. Paired with
     * {@link #vignetteFailed} to keep three states apart: still loading, answered (including "there
     * is no vignette", which is a real answer), and could-not-check.
     */
    @Nullable
    private VignetteInfo vignette;
    private boolean vignetteFailed;
    private boolean vignetteLoading;

    /** Stops a second tap saving the same date twice while the first request is open. */
    private boolean saveInFlight;

    /**
     * The in-app assisted ГТП check. It opens RTA's vin-check page in a WebView; when the user
     * confirms a date scraped from the result, it comes back here and is persisted exactly like a
     * hand-picked one — the WebView never writes anything itself.
     */
    private final ActivityResultLauncher<Intent> vinCheckLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    return;
                }
                String iso = result.getData().getStringExtra(VinCheckActivity.EXTRA_RESULT_DATE);
                if (iso == null || iso.isEmpty()) {
                    return;
                }
                String previous = vehicle.inspectionValidTo;
                vehicle.inspectionValidTo = iso;
                persist(() -> vehicle.inspectionValidTo = previous,
                        getString(R.string.cmp_checked_saved, ComplianceStatus.format(iso)));
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vehicle_compliance);

        client = ApiClient.get(this);

        ScreenInsets.apply(findViewById(R.id.cmpRoot));

        vehicle = (Vehicle) getIntent().getSerializableExtra(EXTRA_VEHICLE);
        if (vehicle == null) {
            finish();
            return;
        }

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        ((TextView) findViewById(R.id.cmpVehicle)).setText(vehicleLabel());

        swipe = findViewById(R.id.cmpRoot);
        SwipeRefresh.theme(swipe);
        swipe.setOnRefreshListener(this::fetchVignette);

        bindAll();
        fetchVignette();
    }

    private String vehicleLabel() {
        String name = ((vehicle.make == null ? "" : vehicle.make) + " "
                + (vehicle.model == null ? "" : vehicle.model)).trim();
        if (vehicle.licensePlate != null && !vehicle.licensePlate.isEmpty()) {
            name = name.isEmpty() ? vehicle.licensePlate : name + " — " + vehicle.licensePlate;
        }
        return name;
    }

    // ---- Binding -------------------------------------------------------------

    private void bindAll() {
        bindVignetteCard();
        bindInspectionCard();
        bindInsuranceCard();
    }

    private void bindVignetteCard() {
        View card = findViewById(R.id.cardVignette);
        // accent_1 (cyan). Amber and green are skipped across all three cards: those are the DUE and
        // OK status colours, and a green chip beside a red "expired" pill reads as a contradiction.
        chip(card, R.drawable.ic_road, 0);
        title(card, R.string.cmp_doc_vignette);

        TextView value = card.findViewById(R.id.cmpValue);
        TextView relative = card.findViewById(R.id.cmpRelative);
        TextView source = card.findViewById(R.id.cmpSource);
        source.setText(R.string.cmp_src_official);
        source.setVisibility(View.VISIBLE);

        MaterialButton primary = card.findViewById(R.id.cmpBtnPrimary);
        MaterialButton secondary = card.findViewById(R.id.cmpBtnSecondary);

        if (!VehicleType.of(vehicle).requiresVignette()) {
            // Exempt (a motorcycle) — see VehicleType.requiresVignette(). Nothing was fetched, so
            // there is nothing to report: a neutral pill, the reason in place of a date, and no
            // buttons at all. Buying one is not an option to offer and checking would only return
            // the authority's „no vignette“, which is exactly the answer that must not be shown here
            // as a red pill. The card stays on screen rather than being hidden, because "what about
            // the vignette?" is a question the user came here with and silence doesn't answer it.
            // No provenance label: no check was made, so neither „официална проверка“ nor
            // „декларирано“ is true, and the two-tier split is only meaningful about actual data.
            source.setVisibility(View.GONE);
            statusPill(card, null, R.string.cmp_st_exempt);
            value.setText(R.string.cmp_vignette_exempt);
            relative.setVisibility(View.VISIBLE);
            relative.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
            relative.setText(R.string.cmp_vignette_exempt_hint);
            details(card, null);
            primary.setVisibility(View.GONE);
            secondary.setVisibility(View.GONE);
            card.findViewById(R.id.cmpRenewRow).setVisibility(View.GONE);
            return;
        }

        // Nothing here is user-editable, so the "change date" slot becomes the buy link. The check
        // slot stays hidden by default — the point of tier 1 is that the app already knows the answer,
        // and offering to re-check an authoritative one invites second-guessing it. The one case where
        // it earns its place is "we couldn't check", handled in that branch below.
        primary.setVisibility(View.VISIBLE);
        primary.setText(R.string.cmp_buy_vignette);
        primary.setIconResource(R.drawable.ic_open_in_new);
        primary.setOnClickListener(v -> openExternal(URL_BUY_VIGNETTE,
                vehicle.licensePlate, R.string.cmp_plate_copied));
        secondary.setVisibility(View.GONE);
        // Nothing to renew by hand here — a vignette is bought, and the next check will see it. The
        // three cards share one layout, so this has to be hidden explicitly rather than left to the
        // XML default.
        card.findViewById(R.id.cmpRenewRow).setVisibility(View.GONE);

        if (vignetteLoading) {
            statusPill(card, null, R.string.cmp_st_unknown);
            value.setText(R.string.cmp_checking);
            relative.setVisibility(View.GONE);
            details(card, null);
            return;
        }

        if (vignetteFailed || vignette == null) {
            // Could not reach the check. NOT the same as "no vignette" — reporting it that way would
            // tell the user their vignette had lapsed every time the network hiccuped.
            statusPill(card, null, R.string.cmp_st_unknown);
            value.setText(R.string.cmp_unavailable);
            relative.setVisibility(View.VISIBLE);
            relative.setTextColor(ContextCompat.getColor(this, R.color.status_due_text));
            relative.setText(R.string.cmp_unavailable_hint);
            details(card, null);
            // The only state where this card offers a check: the app has no answer, so send the user
            // to the authority's own page for one. Read-only by design — it opens a page and copies
            // the plate, and NOTHING comes back into the model. A date the user typed after reading it
            // would make this card declared data wearing tier 1's "официална проверка" label, which is
            // the exact blurring the two-tier split exists to prevent.
            secondary.setVisibility(View.VISIBLE);
            secondary.setText(R.string.cmp_check_official);
            secondary.setIconResource(R.drawable.ic_open_in_new);
            secondary.setOnClickListener(v -> openExternal(URL_CHECK_VIGNETTE,
                    vehicle.licensePlate, R.string.cmp_plate_copied));
            return;
        }

        if (!vignette.isValid()) {
            // A real, actionable answer from the authority: this plate has no live vignette.
            statusPill(card, ComplianceStatus.OVERDUE, R.string.cmp_st_none);
            value.setText(R.string.cmp_no_vignette);
            relative.setVisibility(View.GONE);
            details(card, checkedAtDetail());
            return;
        }

        ComplianceStatus status = ComplianceStatus.of(vignette.validTo, ComplianceStatus.VIGNETTE_DUE_DAYS);
        statusPill(card, status, status == null ? R.string.cmp_st_unknown : status.labelRes);
        bindDate(card, vignette.validTo);

        List<String> facts = new ArrayList<>();
        if (vignette.number != null) {
            facts.add(getString(R.string.cmp_detail_number, vignette.number));
        }
        if (vignette.vehicleClass != null) {
            facts.add(getString(R.string.cmp_detail_class, vignette.vehicleClass));
        }
        if (vignette.emissionsClass != null) {
            facts.add(getString(R.string.cmp_detail_emissions, vignette.emissionsClass));
        }
        String from = ComplianceStatus.format(vignette.validFrom);
        if (from != null) {
            facts.add(getString(R.string.cmp_detail_from, from));
        }
        String checked = checkedAtDetail();
        if (checked != null) {
            facts.add(checked);
        }
        details(card, facts.isEmpty() ? null : join(facts));
    }

    private void bindInspectionCard() {
        View card = findViewById(R.id.cardInspection);
        chip(card, R.drawable.ic_inspection, 2);           // accent_3 (violet)
        title(card, R.string.cmp_doc_inspection);
        ((TextView) card.findViewById(R.id.cmpSource)).setText(R.string.cmp_src_declared);

        ComplianceStatus status = ComplianceStatus.of(vehicle.inspectionValidTo, ComplianceStatus.INSPECTION_DUE_DAYS);
        statusPill(card, status, status == null ? R.string.cmp_st_unknown : status.labelRes);
        bindDate(card, vehicle.inspectionValidTo);
        details(card, null);

        MaterialButton primary = card.findViewById(R.id.cmpBtnPrimary);
        primary.setVisibility(View.VISIBLE);
        primary.setText(R.string.cmp_set_date);
        primary.setIconResource(R.drawable.ic_calendar);
        primary.setOnClickListener(v -> pickDate(R.string.cmp_pick_inspection,
                vehicle.inspectionValidTo, iso -> vehicle.inspectionValidTo = iso));

        MaterialButton secondary = card.findViewById(R.id.cmpBtnSecondary);
        secondary.setVisibility(View.VISIBLE);
        secondary.setText(R.string.cmp_check_official);
        // Opens the assisted check in-app: the VIN-check page in a WebView, VIN on the clipboard,
        // the user solves the reCAPTCHA, and a detected date comes back for confirmation. With no VIN
        // stored the page can't work, so fall back to just opening it in the browser.
        secondary.setOnClickListener(v -> openInspectionCheck());

        bindRenewRow(card, status, R.string.cmp_renew_q_inspection,
                () -> vehicle.inspectionValidTo,
                iso -> vehicle.inspectionValidTo = iso);
    }

    private void bindInsuranceCard() {
        View card = findViewById(R.id.cardInsurance);
        chip(card, R.drawable.ic_shield, 4);               // accent_5 (rose)
        title(card, R.string.cmp_doc_insurance);
        ((TextView) card.findViewById(R.id.cmpSource)).setText(R.string.cmp_src_declared);

        // The policy's own expiry, and separately the next instalment. The worse of the two drives
        // the pill: a policy valid until March is worthless if October's instalment goes unpaid,
        // because a missed instalment terminates cover mid-term.
        ComplianceStatus policy = ComplianceStatus.of(vehicle.insuranceValidTo, ComplianceStatus.INSURANCE_DUE_DAYS);
        ComplianceStatus instalment =
                ComplianceStatus.of(vehicle.insuranceNextInstallment, ComplianceStatus.INSTALLMENT_DUE_DAYS);
        ComplianceStatus worst = ComplianceStatus.worst(policy, instalment);
        statusPill(card, worst, worst == null ? R.string.cmp_st_unknown : worst.labelRes);

        bindDate(card, vehicle.insuranceValidTo);

        String nextInstalment = ComplianceStatus.format(vehicle.insuranceNextInstallment);
        details(card, nextInstalment == null
                ? getString(R.string.cmp_detail_installment_none)
                : getString(R.string.cmp_detail_installment, nextInstalment));

        MaterialButton primary = card.findViewById(R.id.cmpBtnPrimary);
        primary.setVisibility(View.VISIBLE);
        primary.setText(R.string.cmp_set_date);
        primary.setIconResource(R.drawable.ic_calendar);
        primary.setOnClickListener(v -> chooseInsuranceDate());

        MaterialButton secondary = card.findViewById(R.id.cmpBtnSecondary);
        secondary.setVisibility(View.VISIBLE);
        secondary.setText(R.string.cmp_check_official);
        secondary.setOnClickListener(v -> openInsuranceCheck());

        // Driven by `policy`, deliberately not by `worst`. If it is only the instalment that is due,
        // "+1 година" is the wrong answer entirely — instalments are quarterly or monthly — so the
        // user is left with the date picker instead of being offered a renewal that would be wrong.
        bindRenewRow(card, policy, R.string.cmp_renew_q_insurance,
                () -> vehicle.insuranceValidTo,
                iso -> vehicle.insuranceValidTo = iso);
    }

    /** Reads the field a renewal extends, so bindRenewRow can compute from the current value. */
    private interface DateSource {
        String get();
    }

    /**
     * Shows the "did you renew it?" prompt for a declared document that is due or already expired,
     * and hides it otherwise.
     *
     * <p>Only offered for {@link ComplianceStatus#DUE} and {@link ComplianceStatus#OVERDUE}. On a
     * healthy document it would be clutter, and on one with no date at all there is nothing to
     * extend — that case wants the picker.
     */
    private void bindRenewRow(View card, ComplianceStatus status, int questionRes,
                              DateSource source, DateSink sink) {
        View row = card.findViewById(R.id.cmpRenewRow);
        if (status != ComplianceStatus.DUE && status != ComplianceStatus.OVERDUE) {
            row.setVisibility(View.GONE);
            return;
        }
        row.setVisibility(View.VISIBLE);
        ((TextView) card.findViewById(R.id.cmpRenewQuestion)).setText(questionRes);
        card.findViewById(R.id.cmpBtnRenew).setOnClickListener(v -> renew(source, sink));
    }

    /**
     * Extends a declared document by a year and saves it. One tap replaces opening the picker and
     * paging twelve months forward, which is the single most predictable date edit in the app.
     */
    private void renew(DateSource source, DateSink sink) {
        String previousInspection = vehicle.inspectionValidTo;
        String previousInsurance = vehicle.insuranceValidTo;
        String previousInstalment = vehicle.insuranceNextInstallment;

        String renewed = ComplianceStatus.plusOneYear(source.get());
        sink.accept(renewed);
        persist(() -> {
            vehicle.inspectionValidTo = previousInspection;
            vehicle.insuranceValidTo = previousInsurance;
            vehicle.insuranceNextInstallment = previousInstalment;
        }, getString(R.string.cmp_renewed, ComplianceStatus.format(renewed)));
    }

    /** Writes the expiry date + "N days left" pair shared by all three cards. */
    private void bindDate(View card, String isoDate) {
        TextView value = card.findViewById(R.id.cmpValue);
        TextView relative = card.findViewById(R.id.cmpRelative);

        String formatted = ComplianceStatus.format(isoDate);
        if (formatted == null) {
            value.setText(R.string.cmp_no_date);
            relative.setVisibility(View.GONE);
            return;
        }
        value.setText(formatted);

        Integer days = ComplianceStatus.daysUntil(isoDate);
        if (days == null) {
            relative.setVisibility(View.GONE);
            return;
        }
        relative.setVisibility(View.VISIBLE);
        if (days == 0) {
            relative.setText(R.string.cmp_expires_today);
        } else if (days > 0) {
            relative.setText(getResources().getQuantityString(R.plurals.cmp_days_left, days, days));
        } else {
            relative.setText(getResources()
                    .getQuantityString(R.plurals.cmp_days_overdue, -days, -days));
        }
        // A tint on the page background, so it must be the status_*_text flavour, never the fill.
        ComplianceStatus s = days < 0 ? ComplianceStatus.OVERDUE : ComplianceStatus.OK;
        relative.setTextColor(ContextCompat.getColor(this, s.textColorRes));
    }

    private void chip(View card, int iconRes, int accentIndex) {
        ImageView icon = card.findViewById(R.id.cmpIcon);
        int[] fg = getResources().getIntArray(R.array.accent_fg);
        int[] bg = getResources().getIntArray(R.array.accent_bg);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(fg[accentIndex % fg.length]));
        icon.setBackgroundTintList(ColorStateList.valueOf(bg[accentIndex % bg.length]));
    }

    private void title(View card, int titleRes) {
        ((TextView) card.findViewById(R.id.cmpTitle)).setText(titleRes);
    }

    /**
     * Tints the pill and picks a readable label colour for it. {@code status == null} means unknown
     * and gets a neutral outline fill — white text on that fails in the dark scheme, so the label
     * switches to {@code colorOnSurfaceVariant} instead of keeping the XML default.
     */
    private void statusPill(View card, @Nullable ComplianceStatus status, int labelRes) {
        TextView pill = card.findViewById(R.id.cmpStatus);
        pill.setText(labelRes);
        if (status == null) {
            pill.setBackgroundTintList(ColorStateList.valueOf(
                    themeColor(com.google.android.material.R.attr.colorOutlineVariant)));
            pill.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        } else {
            pill.setBackgroundTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(this, status.colorRes)));
            pill.setTextColor(ContextCompat.getColor(this, R.color.white));
        }
    }

    private void details(View card, @Nullable String text) {
        TextView details = card.findViewById(R.id.cmpDetails);
        if (text == null || text.isEmpty()) {
            details.setVisibility(View.GONE);
        } else {
            details.setVisibility(View.VISIBLE);
            details.setText(text);
        }
    }

    /**
     * Resolves a theme colour role to an int. With {@code resolveRefs} on, an M3 role can come back
     * either as the resource it points at or as a literal ARGB value, so both cases are handled —
     * feeding a raw colour int to {@code getColor} as if it were a resource id would throw.
     */
    private int themeColor(int attr) {
        TypedValue tv = new TypedValue();
        if (!getTheme().resolveAttribute(attr, tv, true)) {
            return ContextCompat.getColor(this, R.color.status_due);   // role missing: visible, not crashy
        }
        return tv.resourceId != 0 ? ContextCompat.getColor(this, tv.resourceId) : tv.data;
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    /** "Проверено 18.08 10:42", or null when the backend sent no (or an unreadable) timestamp. */
    @Nullable
    private String checkedAtDetail() {
        if (vignette == null || vignette.checkedAt == null) {
            return null;
        }
        String raw = vignette.checkedAt.trim();
        int dot = raw.indexOf('.');
        if (dot > 0) {
            raw = raw.substring(0, dot);   // drop fractional seconds
        }
        raw = raw.replace("Z", "");
        if (raw.length() > 19) {
            raw = raw.substring(0, 19);    // drop a trailing offset
        }
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        in.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Date d = in.parse(raw);
            if (d == null) {
                return null;
            }
            // Rendered in the device's zone: "when was this checked" is a local question.
            return getString(R.string.cmp_detail_checked,
                    new SimpleDateFormat("dd.MM HH:mm", Locale.US).format(d));
        } catch (ParseException e) {
            return null;   // better no line at all than a raw timestamp
        }
    }

    // ---- Editing the declared dates -----------------------------------------

    /** Receives the picked date as {@code yyyy-MM-dd}. */
    private interface DateSink {
        void accept(String iso);
    }

    /**
     * A policy carries two dates the user may want to change, so the button opens a chooser rather
     * than guessing. Clearing is offered only for the instalment — after the final one there is no
     * next date, whereas an inspection or a policy always has an expiry.
     */
    private void chooseInsuranceDate() {
        String[] options = {
                getString(R.string.cmp_pick_insurance),
                getString(R.string.cmp_pick_installment),
                getString(R.string.cmp_clear_date),
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cmp_which_date)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        pickDate(R.string.cmp_pick_insurance, vehicle.insuranceValidTo,
                                iso -> vehicle.insuranceValidTo = iso);
                    } else if (which == 1) {
                        pickDate(R.string.cmp_pick_installment, vehicle.insuranceNextInstallment,
                                iso -> vehicle.insuranceNextInstallment = iso);
                    } else {
                        String previous = vehicle.insuranceNextInstallment;
                        vehicle.insuranceNextInstallment = "";
                        persist(() -> vehicle.insuranceNextInstallment = previous);
                    }
                })
                .show();
    }

    /**
     * Opens the Material date picker on the current value and persists the result.
     *
     * <p>The picker works in UTC — it hands back UTC midnight and {@code setSelection} expects the
     * same — so both directions convert in UTC here. That is not the zone
     * {@link ComplianceStatus#daysUntil} uses, and the difference is deliberate: this is a
     * string/millis conversion, that one asks "how many days from the user's today".
     */
    private void pickDate(int titleRes, @Nullable String current, DateSink sink) {
        MaterialDatePicker.Builder<Long> builder = MaterialDatePicker.Builder.datePicker()
                .setTitleText(titleRes);
        Long selection = isoToUtcMillis(current);
        if (selection != null) {
            builder.setSelection(selection);
        }
        MaterialDatePicker<Long> picker = builder.build();
        picker.addOnPositiveButtonClickListener(millis -> {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            String previousInspection = vehicle.inspectionValidTo;
            String previousInsurance = vehicle.insuranceValidTo;
            String previousInstalment = vehicle.insuranceNextInstallment;
            sink.accept(fmt.format(new Date(millis)));
            persist(() -> {
                // Restores all three rather than tracking which one the sink wrote: the whole vehicle
                // is what gets POSTed, so the whole vehicle is what has to roll back.
                vehicle.inspectionValidTo = previousInspection;
                vehicle.insuranceValidTo = previousInsurance;
                vehicle.insuranceNextInstallment = previousInstalment;
            });
        });
        picker.show(getSupportFragmentManager(), "cmp_date_picker");
    }

    @Nullable
    private static Long isoToUtcMillis(@Nullable String iso) {
        if (iso == null || iso.trim().isEmpty()) {
            return null;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        fmt.setLenient(false);
        try {
            Date d = fmt.parse(iso.trim());
            return d == null ? null : d.getTime();
        } catch (ParseException e) {
            return null;
        }
    }

    /**
     * POSTs the whole vehicle to {@code /vehicles/update}. The date is already written into
     * {@link #vehicle} so the card can re-bind immediately; {@code rollback} puts it back if the
     * server refuses, rather than leaving a figure on screen that was never stored.
     */
    private void persist(Runnable rollback) {
        persist(rollback, getString(R.string.cmp_saved));
    }

    /**
     * @param successMessage what to toast on success. A renewal says the new date out loud
     *                       ("Обновено до 18.08.2027") because the user never typed it and would
     *                       otherwise have to go looking to confirm what the app just decided.
     */
    private void persist(Runnable rollback, String successMessage) {
        if (saveInFlight) {
            return;
        }
        bindAll();   // optimistic: the user sees their date land straight away

        String body;
        try {
            body = vehicle.toUpdateJson().toString();
        } catch (JSONException e) {
            rollback.run();
            bindAll();
            Toast.makeText(this, R.string.cmp_save_error, Toast.LENGTH_SHORT).show();
            return;
        }

        saveInFlight = true;
        Request request = new Request.Builder()
                .url(UPDATE_URL)
                .post(RequestBody.create(body, JSON))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Compliance", "POST /vehicles/update failed", e);
                // Not retried: unlike the GETs this mutates, and the user is standing right here and
                // can simply pick the date again.
                runOnUiThread(() -> {
                    saveInFlight = false;
                    rollback.run();
                    bindAll();
                    Toast.makeText(VehicleComplianceActivity.this,
                            R.string.cmp_save_error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) {
                boolean ok;
                try (Response r = response) {
                    ok = r.isSuccessful();
                }
                runOnUiThread(() -> {
                    saveInFlight = false;
                    if (!ok) {
                        rollback.run();
                        bindAll();
                        Toast.makeText(VehicleComplianceActivity.this,
                                R.string.cmp_save_error, Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(VehicleComplianceActivity.this,
                            successMessage, Toast.LENGTH_SHORT).show();
                    // Hands the updated vehicle back so the detail screen re-binds and MainActivity
                    // reloads — the same contract EditVehicleActivity uses.
                    Intent data = new Intent();
                    data.putExtra(EXTRA_RESULT_VEHICLE, vehicle);
                    setResult(RESULT_OK, data);
                });
            }
        });
    }

    // ---- Vignette fetch ------------------------------------------------------

    private void fetchVignette() {
        if (!VehicleType.of(vehicle).requiresVignette()) {
            // Exempt: no request, and no "could not check" state either — the card renders its own
            // exempt branch. The refresh spinner still has to be stopped, since a pull-to-refresh is
            // how this can be reached with nothing to fetch.
            vignetteLoading = false;
            vignetteFailed = false;
            vignette = null;
            if (swipe != null) {
                swipe.setRefreshing(false);
            }
            bindVignetteCard();
            return;
        }
        if (vehicle.id == null || vehicle.id.isEmpty()) {
            deliverVignette(null, true);
            return;
        }
        vignetteLoading = true;
        vignetteFailed = false;
        bindVignetteCard();
        fetchVignette(1);
    }

    /**
     * {@code GET /vehicles/vignette?id=} — our backend's cached proxy of the toll authority's plate
     * check. The vehicle id is what travels, never the plate: the server already holds the plate and
     * derives the owner from the Bearer token, which stops a client probing arbitrary plates through
     * our infrastructure.
     */
    private void fetchVignette(int attempt) {
        HttpUrl url = HttpUrl.parse(VIGNETTE_URL).newBuilder()
                .addQueryParameter("id", vehicle.id)
                .build();
        Request request = new Request.Builder().url(url).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Compliance", "GET /vehicles/vignette failed (attempt " + attempt + ")", e);
                if (attempt < VIGNETTE_MAX_ATTEMPTS) {
                    fetchVignette(attempt + 1);
                    return;
                }
                deliverVignette(null, true);
            }

            @Override
            public void onResponse(Call call, Response response) {
                VignetteInfo info = null;
                boolean retriable = false;
                try (Response r = response) {
                    if (r.isSuccessful() && r.body() != null) {
                        info = VignetteInfo.fromJson(r.body().string());
                    }
                    // A non-2xx is the backend saying it could not reach the authority and has no
                    // cached answer. That is "unknown", not "no vignette" — so it falls through to
                    // deliverVignette(null, true) instead of rendering as a lapsed vignette.
                } catch (IOException e) {
                    retriable = true;   // truncated body — worth another attempt
                    Log.e("Compliance", "vignette read failed (attempt " + attempt + ")", e);
                } catch (JSONException e) {
                    Log.e("Compliance", "vignette parse failed", e);
                }

                if (info == null) {
                    if (retriable && attempt < VIGNETTE_MAX_ATTEMPTS) {
                        fetchVignette(attempt + 1);
                        return;
                    }
                    deliverVignette(null, true);
                    return;
                }
                deliverVignette(info, false);
            }
        });
    }

    /** Every fetch path ends here, success or give-up, so the refresh spinner always stops. */
    private void deliverVignette(@Nullable VignetteInfo info, boolean failed) {
        runOnUiThread(() -> {
            vignetteLoading = false;
            vignette = info;
            vignetteFailed = failed;
            if (swipe != null) {
                swipe.setRefreshing(false);
            }
            bindVignetteCard();
        });
    }

    // ---- Leaving for an official page ---------------------------------------

    /**
     * Launches the assisted ГТП check. With a VIN stored it opens the in-app WebView (which copies
     * the VIN, prefills it and can read the result back); without one the VIN-check page is useless,
     * so it just opens in the browser for the user to sort out manually.
     */
    private void openInspectionCheck() {
        if (isBlank(vehicle.vin)) {
            openExternal(URL_CHECK_INSPECTION, null, R.string.cmp_vin_copied);
            return;
        }
        launchCheck(URL_CHECK_INSPECTION, HOST_INSPECTION, vehicle.vin,
                new String[]{
                        "input[formcontrolname*=\"vin\" i]",
                        "input[name*=\"vin\" i]",
                        "input[id*=\"vin\" i]",
                        "input[placeholder*=\"\u0440\u0430\u043c\" i]",
                        "input[placeholder*=\"vin\" i]",
                },
                getString(R.string.vc_title_inspection),
                getString(R.string.vc_hint_inspection));
    }

    /**
     * Opens the Guarantee Fund's ГО check <b>in the browser</b>, with the identifier on the clipboard.
     *
     * <p><b>This deliberately does not use {@link VinCheckActivity}, unlike ГТП.</b> It did, and the
     * page loaded only intermittently inside the WebView while RTA's never failed once. Two causes on
     * the Fund's side were found and worked around — render-blocking third-party assets (Google Fonts
     * in {@code <head>}, a blocking {@code code.jquery.com} script) stubbed out, and a race in their
     * own {@code window.load} handler that leaves the ALTCHA widget unconfigured — and it still failed
     * on device. Whatever remains is inside their page and not reachable from our side.
     *
     * <p>So ГО gets the same honest treatment as the vignette-purchase link: the official page in the
     * user's own browser, which is a full Chrome with none of the WebView's constraints, and the value
     * pre-copied so the only manual step is a paste. The date then comes back through the picker or the
     * „+1 година“ renewal row. <b>Do not re-introduce the WebView here</b> without evidence the Fund's
     * page has changed.
     *
     * <p>The Fund accepts VIN <em>or</em> plate and its own instructions say the VIN gives the fullest
     * result, so the VIN is copied when we have one and the plate otherwise.
     */
    private void openInsuranceCheck() {
        if (!isBlank(vehicle.vin)) {
            openExternal(URL_CHECK_INSURANCE, vehicle.vin, R.string.cmp_vin_copied);
        } else {
            openExternal(URL_CHECK_INSURANCE, vehicle.licensePlate, R.string.cmp_plate_copied);
        }
    }

    private void launchCheck(String url, String host, String fillValue, String[] selectors,
                             String title, String hint) {
        Intent intent = new Intent(this, VinCheckActivity.class);
        intent.putExtra(VinCheckActivity.EXTRA_URL, url);
        intent.putExtra(VinCheckActivity.EXTRA_ALLOWED_HOST, host);
        intent.putExtra(VinCheckActivity.EXTRA_FILL_VALUE, fillValue);
        intent.putExtra(VinCheckActivity.EXTRA_FILL_SELECTORS, selectors);
        intent.putExtra(VinCheckActivity.EXTRA_TITLE, title);
        intent.putExtra(VinCheckActivity.EXTRA_HINT, hint);
        vinCheckLauncher.launch(intent);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Opens an official page in the browser, first putting {@code copyValue} on the clipboard so the
     * user can paste it into the page's field.
     *
     * <p>None of the three pages accepts the vehicle identifier as a URL parameter, so a paste is as
     * close to automation as they allow: the VIN-check and ГО pages take the value we copy, and the
     * vignette-purchase page is pre-filled by pasting the plate. The value differs by page — the VIN
     * for the ГТП check, the plate for the other two — which is why the caller passes both the value
     * and the toast string rather than this method assuming the plate.
     *
     * @param copyValue    what to place on the clipboard; nothing is copied when null/empty (e.g. a
     *                     vehicle with no VIN stored) — the page still opens, just without the assist
     * @param copiedMsgRes toast string with a single {@code %1$s} for the copied value
     */
    private void openExternal(String url, String copyValue, int copiedMsgRes) {
        if (copyValue != null && !copyValue.trim().isEmpty()) {
            String value = copyValue.trim();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(value, value));
                // Android 13+ shows its own copy confirmation; a toast on top would duplicate it.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this, getString(copiedMsgRes, value), Toast.LENGTH_SHORT).show();
                }
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.cmp_no_browser, Toast.LENGTH_SHORT).show();
        }
    }
}
