package com.example.maintenanceapp.util;

import android.content.Context;

import com.example.maintenanceapp.R;
import com.example.maintenanceapp.model.Vehicle;

import java.util.Locale;

/** currently possible vehicle types: car, motorcycle, van, truck */
public enum VehicleType {

    CAR("car", R.string.vt_car, R.string.vt_group_car,
            R.drawable.ic_car, R.drawable.car_placeholder),
    MOTORCYCLE("motorcycle", R.string.vt_motorcycle, R.string.vt_group_motorcycle,
            R.drawable.ic_motorcycle, R.drawable.motorcycle_placeholder),
    VAN("van", R.string.vt_van, R.string.vt_group_van,
            R.drawable.ic_van, R.drawable.van_placeholder),
    TRUCK("truck", R.string.vt_truck, R.string.vt_group_truck,
            R.drawable.ic_truck, R.drawable.truck_placeholder);

    /** JSON like body */
    public final String apiValue;
    public final int labelRes;
    public final int groupLabelRes;
    public final int iconRes;
    public final int placeholderRes;
    public static final VehicleType DEFAULT = CAR;

    VehicleType(String apiValue, int labelRes, int groupLabelRes, int iconRes, int placeholderRes) {
        this.apiValue = apiValue;
        this.labelRes = labelRes;
        this.groupLabelRes = groupLabelRes;
        this.iconRes = iconRes;
        this.placeholderRes = placeholderRes;
    }

    public static VehicleType fromApi(String value) {
        if (value == null) {
            return DEFAULT;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return DEFAULT;
        }
        for (VehicleType t : values()) {
            if (t.apiValue.equals(v)) {
                return t;
            }
        }
        switch (v) {
            case "motorbike":
            case "moto":
            case "bike":
            case "scooter":
                return MOTORCYCLE;
            case "minivan":
            case "bus":
            case "minibus":
                return VAN;
            case "lorry":
            case "hgv":
                return TRUCK;
            default:
                return DEFAULT;
        }
    }

    public static VehicleType of(Vehicle vehicle) {
        return vehicle == null ? DEFAULT : fromApi(vehicle.vehicleType);
    }

    public static VehicleType at(int index) {
        VehicleType[] all = values();
        return index < 0 || index >= all.length ? DEFAULT : all[index];
    }

    public static String[] labels(Context ctx) {
        VehicleType[] all = values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            out[i] = ctx.getString(all[i].labelRes);
        }
        return out;
    }

    /** Whether the oil advisor may answer for this type. */
    public boolean supportsOilAdvisor() {
        return this == CAR;
    }

    /** currently only MOTORCYCLEs does not require Vignettes. */
    public boolean requiresVignette() {
        return this != MOTORCYCLE;
    }
}
