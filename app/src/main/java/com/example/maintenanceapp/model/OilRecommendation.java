package com.example.maintenanceapp.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The answer from {@code GET /oil/recommend} for one engine.
 *
 * <p>Two fields carry the actual advice and they are not equally important: {@link #viscosity} is
 * the SAE grade and {@link #specs} are the OEM approvals. The approvals are the binding requirement
 * — a 5W-30 without VW 507 00 will still wreck a DPF — so the screen shows them as a labelled row
 * of their own rather than folding them into the grade, and {@link #products} is explicitly the
 * <em>least</em> load-bearing part of the payload (brands are interchangeable, an approval is not).
 *
 * <p>Numeric "unknown" is negative / zero throughout, never a plausible-looking default: a made-up
 * oil capacity is worse than no capacity, same rule as {@code MaintenanceItem.cost}.
 */
public class OilRecommendation {

    public String engineCode = "";
    public String engineName = "";

    /** Primary SAE grade, e.g. {@code 5W-30}. */
    public String viscosity = "";

    /** Optional second grade the engine also accepts (high mileage, hot climate, older spec). */
    public String altViscosity = "";

    /** OEM approvals / ACEA classes. The part that actually binds. */
    public final List<String> specs = new ArrayList<>();

    /** Sump capacity including the filter, litres. Negative = not recorded. */
    public double capacityLiters = -1;

    /** Recommended change interval. {@code 0} = not recorded. */
    public int intervalKm;
    public int intervalMonths;

    /** Free-text caveat from the catalog (e.g. LPG, DPF, known PD-camshaft wear). May be empty. */
    public String note = "";

    public final List<Product> products = new ArrayList<>();

    /** One concrete oil the catalog suggests. Illustrative — the approvals above are the rule. */
    public static class Product {
        public String name = "";
        public String viscosity = "";
        public String specs = "";
    }

    public static OilRecommendation fromJson(JSONObject o) {
        OilRecommendation r = new OilRecommendation();
        r.engineCode = o.optString("engineCode", "");
        r.engineName = o.optString("engineName", "");
        r.viscosity = o.optString("viscosity", "");
        r.altViscosity = o.optString("altViscosity", "");
        r.note = o.optString("note", "");

        JSONArray specs = o.optJSONArray("specs");
        if (specs != null) {
            for (int i = 0; i < specs.length(); i++) {
                String s = specs.optString(i, "");
                if (!s.isEmpty()) r.specs.add(s);
            }
        }

        // optDouble/optInt with an out-of-band default, so "the server didn't send it" stays
        // distinguishable from a real value.
        r.capacityLiters = o.optDouble("capacityLiters", -1);
        if (Double.isNaN(r.capacityLiters)) {
            r.capacityLiters = -1;
        }
        r.intervalKm = Math.max(0, o.optInt("intervalKm", 0));
        r.intervalMonths = Math.max(0, o.optInt("intervalMonths", 0));

        JSONArray products = o.optJSONArray("products");
        if (products != null) {
            for (int i = 0; i < products.length(); i++) {
                JSONObject p = products.optJSONObject(i);
                if (p == null) continue;
                Product product = new Product();
                product.name = p.optString("name", "");
                product.viscosity = p.optString("viscosity", "");
                product.specs = p.optString("specs", "");
                if (!product.name.isEmpty()) r.products.add(product);
            }
        }
        return r;
    }

    /** True when the payload carries the one thing the screen cannot render without. */
    public boolean isUsable() {
        return !viscosity.isEmpty() || !specs.isEmpty();
    }
}
