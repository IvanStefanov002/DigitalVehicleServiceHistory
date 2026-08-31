/*
 * Vehicle.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

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
    public String vehicleType = "car"; // default car
    public String country = "BG"; // default BG
    public String inspectionValidTo; // GTP
    public String insuranceValidTo; // Insurance
    public String insuranceNextInstallment;

    public Vehicle(String make, String model) {
        this.make = make;
        this.model = model;
    }

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
