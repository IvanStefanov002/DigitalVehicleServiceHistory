package com.example.maintenanceapp.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class EngineOption implements Serializable {

    /** Declarations:
     * Manufacturer engine code.
     * Human label for the dropdown.
     * Fuel type
     */
    public String code = "";
    public String displayName = "";
    public String fuelType = "";
    public final List<String> makes = new ArrayList<>();

    public static EngineOption fromJson(JSONObject o) {
        EngineOption e = new EngineOption();
        e.code = o.optString("code", "").toUpperCase(java.util.Locale.US);
        e.displayName = o.optString("displayName", "");
        e.fuelType = o.optString("fuelType", "");
        JSONArray makes = o.optJSONArray("makes");
        if (makes != null) {
            for (int i = 0; i < makes.length(); i++) {
                String m = makes.optString(i, "");
                if (!m.isEmpty()) e.makes.add(m);
            }
        }
        return e;
    }

    public String label() {
        if (displayName.isEmpty()) {
            return code;
        }
        return displayName.contains(code) ? displayName : displayName + " (" + code + ")";
    }

    public boolean fitsMake(String make) {
        if (make == null || make.trim().isEmpty()) {
            return false;
        }
        String needle = make.trim().toLowerCase(java.util.Locale.ROOT);
        for (String m : makes) {
            String hay = m.toLowerCase(java.util.Locale.ROOT);
            if (hay.contains(needle) || needle.contains(hay)) {
                return true;
            }
        }
        return false;
    }
}
