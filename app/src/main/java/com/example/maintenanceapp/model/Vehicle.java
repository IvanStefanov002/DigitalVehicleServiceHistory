package com.example.maintenanceapp.model;

import com.example.maintenanceapp.util.VehicleType;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;

public class Vehicle implements Serializable {
    public String id;          // assigned by the backend
    public String make;
    public String model;
    public int year;
    public String licensePlate;
    public int mileage;        // odometer, km
    public String fuelType;
    public String vin;
    public String color;
    public String imageName;   // name of a bundled res/drawable (e.g. "bmw_320d"); may be null/empty
    public String imageBase64; // the photo itself, base64-encoded; wins over imageName when present

    /**
     * What kind of vehicle this is, as the lowercase wire value of
     * {@link com.example.maintenanceapp.util.VehicleType} ({@code car} / {@code motorcycle} /
     * {@code van} / {@code truck}). Never read this string directly — go through
     * {@code VehicleType.of(vehicle)}, which is where "absent or unrecognised means car" lives.
     *
     * <p>Defaulted to {@code car} rather than left null because every vehicle that existed before
     * this column did is one, and because a null here would have to be special-cased at each of the
     * three places that group, label and gate on it.
     */
    public String vehicleType = "car";

    /**
     * ISO country of registration ({@code BG} by default). Needed because the vignette check is
     * keyed on plate <em>and</em> country — a plate is only unique within its register.
     */
    public String country = "BG";

    // ---- User-declared document expiry dates (yyyy-MM-dd, empty when not set) -------------------
    //
    // These are plain vehicle columns rather than a separate endpoint, so they ride along on the
    // existing GET /vehicles and POST /vehicles/update. They are *declared*, not verified: both
    // authorities (АА for ГТП, Гаранционен фонд for ГО) gate their public check behind a
    // server-enforced captcha, so the app cannot confirm them and must never present them as
    // authoritative the way it does the vignette.

    /** Periodic technical inspection (ГТП) valid-to date. */
    public String inspectionValidTo;

    /** Third-party liability insurance (Гражданска отговорност) valid-to date. */
    public String insuranceValidTo;

    /**
     * Due date of the next insurance installment, when the policy is paid in instalments. Tracked
     * separately from {@link #insuranceValidTo} because a missed instalment terminates cover
     * mid-term — an "expires in March" reminder would stay silent through it.
     */
    public String insuranceNextInstallment;

    public Vehicle(String make, String model) {
        this.make = make;
        this.model = model;
    }

    /**
     * Parses one element of the {@code GET /vehicles} array. Lives here rather than in an Activity
     * so the background reminder worker reads the payload exactly the same way the UI does.
     */
    public static Vehicle fromJson(JSONObject o) {
        Vehicle v = new Vehicle(o.optString("make"), o.optString("model"));
        v.id = o.optString("id");
        v.year = o.optInt("year", 0);
        v.licensePlate = o.optString("licensePlate");
        v.mileage = o.optInt("mileage", 0);
        v.fuelType = o.optString("fuelType");
        v.vin = o.optString("vin");
        v.color = o.optString("color");
        v.imageName = o.optString("imageName");
        v.imageBase64 = o.optString("imageBase64");
        // Absent on an older server build; VehicleType.fromApi reads that as a car.
        v.vehicleType = o.optString("vehicleType");
        // Older server builds don't send these; an empty string reads as "not set" everywhere.
        v.country = o.optString("country", "BG");
        if (v.country.isEmpty()) {
            v.country = "BG";
        }
        v.inspectionValidTo = o.optString("inspectionValidTo");
        v.insuranceValidTo = o.optString("insuranceValidTo");
        v.insuranceNextInstallment = o.optString("insuranceNextInstallment");
        return v;
    }

    /**
     * The body {@code POST /vehicles/update} expects. That route rewrites every column, so it must
     * always carry the <em>whole</em> vehicle — which is why this lives on the model instead of
     * being assembled at each call site: {@code EditVehicleActivity} and
     * {@code VehicleComplianceActivity} each edit a different subset, and a hand-rolled body at
     * either would silently blank the fields the other owns.
     *
     * <p>{@code imageBase64} is deliberately <em>not</em> sent — the photo is set at add time and
     * round-tripping a few hundred KB through every field edit is exactly the payload shape this
     * server truncates.
     */
    public JSONObject toUpdateJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", orEmpty(id));
        json.put("make", orEmpty(make));
        json.put("model", orEmpty(model));
        json.put("year", year);
        json.put("licensePlate", orEmpty(licensePlate));
        json.put("mileage", mileage);
        json.put("fuelType", orEmpty(fuelType));
        json.put("vin", orEmpty(vin));
        json.put("color", orEmpty(color));
        json.put("vehicleType", VehicleType.fromApi(vehicleType).apiValue);
        json.put("country", country == null || country.isEmpty() ? "BG" : country);
        json.put("inspectionValidTo", orEmpty(inspectionValidTo));
        json.put("insuranceValidTo", orEmpty(insuranceValidTo));
        json.put("insuranceNextInstallment", orEmpty(insuranceNextInstallment));
        return json;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
