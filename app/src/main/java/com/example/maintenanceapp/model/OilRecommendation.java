package com.example.maintenanceapp.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class OilRecommendation {

    public String engineCode = "";
    public String engineName = "";
    public String viscosity = "";
    public String altViscosity = "";
    public final List<String> specs = new ArrayList<>();
    public double capacityLiters = -1;
    public int intervalKm;
    public int intervalMonths;
    public String note = "";
    public final List<Product> products = new ArrayList<>();

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
