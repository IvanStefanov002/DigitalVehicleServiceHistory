package com.example.maintenanceapp.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * One row of the engine catalog served by {@code GET /oil/engines} — the lookup key of the oil
 * advisor.
 *
 * <p>The catalog is a convenience, not a gate: {@code OilRecommendationActivity} keeps its engine
 * field typeable, so a code that isn't in the table yet (or a catalog fetch that failed) can still
 * be sent. That's why {@link #code} is the only field the app really needs — everything else here
 * exists to make the dropdown readable.
 */
public class EngineOption implements Serializable {

    /** Manufacturer engine code, e.g. {@code EA288}, {@code M57}, {@code ARL}. Uppercase. */
    public String code = "";

    /** Human label for the dropdown, e.g. {@code 2.0 TDI (EA288)}. Falls back to {@link #code}. */
    public String displayName = "";

    /** {@code diesel} / {@code petrol} / {@code lpg} — the API's vocabulary, not the UI's. */
    public String fuelType = "";

    /** Makes this engine was fitted to, used to float the user's own brand to the top of the list. */
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

    /** What the dropdown shows. The code is always in it — that's what the user is choosing. */
    public String label() {
        if (displayName.isEmpty()) {
            return code;
        }
        return displayName.contains(code) ? displayName : displayName + " (" + code + ")";
    }

    /** True when this engine was fitted to {@code make} (case-insensitive, substring both ways). */
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
