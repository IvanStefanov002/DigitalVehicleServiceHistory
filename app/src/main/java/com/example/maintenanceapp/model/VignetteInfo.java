package com.example.maintenanceapp.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;

/**
 * One vehicle's e-vignette as reported by {@code GET /vehicles/vignette?id=}, which our backend
 * proxies (and caches) from the National Toll Administration's public check service.
 *
 * <p><b>Three outcomes, not two.</b> {@link #STATUS_VALID} and {@link #STATUS_NONE} are both real
 * answers from the authority — "there is a live vignette" and "there is none" — while a failed
 * request is <em>unknown</em> and is represented by the caller getting no {@code VignetteInfo} at
 * all. Collapsing unknown into {@code NONE} would tell a user their vignette had lapsed every time
 * the network hiccuped, which is the same mistake the {@code fetchVehicles} null-vs-empty rule
 * exists to prevent.
 */
public class VignetteInfo implements Serializable {

    /** A live vignette exists; {@link #validTo} is set. */
    public static final String STATUS_VALID = "VALID";

    /** The authority has no valid vignette on record for this plate. Actionable, not an error. */
    public static final String STATUS_NONE = "NONE";

    public String status = STATUS_NONE;

    public String validFrom;      // yyyy-MM-dd, may be null
    public String validTo;        // yyyy-MM-dd, null when status is NONE
    public String number;         // the vignette's identification number, may be null
    public String vehicleClass;   // toll category, may be null
    public String emissionsClass; // EURO class, may be null

    /**
     * When our backend actually asked the authority, as an ISO-8601 instant. Shown to the user
     * because a cached answer minutes old and one from yesterday are worth distinguishing.
     */
    public String checkedAt;

    /** True when the backend answered from its cache rather than a live upstream call. */
    public boolean cached;

    /**
     * What was paid for the vignette. <b>Negative means "not reported"</b> — the same convention as
     * {@link MaintenanceItem#cost}, so display sites must check {@code >= 0} first.
     */
    public double price = -1;

    public boolean isValid() {
        return STATUS_VALID.equals(status);
    }

    public static VignetteInfo fromJson(String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        VignetteInfo v = new VignetteInfo();
        v.status = o.optString("status", STATUS_NONE);
        v.validFrom = emptyToNull(o.optString("validFrom", ""));
        v.validTo = emptyToNull(o.optString("validTo", ""));
        v.number = emptyToNull(o.optString("vignetteNumber", ""));
        v.vehicleClass = emptyToNull(o.optString("vehicleClass", ""));
        v.emissionsClass = emptyToNull(o.optString("emissionsClass", ""));
        v.checkedAt = emptyToNull(o.optString("checkedAt", ""));
        v.cached = o.optBoolean("cached", false);
        // -1 covers both an absent key and a JSON null, which optDouble's fallback does not.
        v.price = o.isNull("price") ? -1 : o.optDouble("price", -1);
        return v;
    }

    private static String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
