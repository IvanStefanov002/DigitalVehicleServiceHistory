package com.example.maintenanceapp.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;

public class VignetteInfo implements Serializable {

    public static final String STATUS_VALID = "VALID";
    public static final String STATUS_NONE = "NONE";
    public String status = STATUS_NONE;

    public String validFrom;      // yyyy-MM-dd, may be null
    public String validTo;        // yyyy-MM-dd, null when status is NONE
    public String number;         // the vignette's identification number, may be null
    public String vehicleClass;   // toll category, may be null
    public String emissionsClass; // EURO class, may be null
    public String checkedAt;
    public boolean cached;
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
