package com.example.maintenanceapp.util;

import android.content.Context;

import com.example.maintenanceapp.R;
import com.example.maintenanceapp.model.Vehicle;

import java.util.Locale;

/**
 * What kind of vehicle a {@link Vehicle} is. The wire value is the lowercase {@link #apiValue};
 * the labels are Bulgarian and live in {@code strings.xml}.
 *
 * <p>Two things about this enum are load-bearing:
 *
 * <ul>
 *   <li><b>An unknown or missing type reads as {@link #CAR}.</b> Every vehicle added before this
 *       field existed is a car, and the server may be a build behind the app — so the fallback has
 *       to be the overwhelmingly common case rather than an "unknown" bucket that would put a
 *       „Друго“ section header over somebody's whole fleet. The cost is that a type the client
 *       doesn't know yet is shown as a car; keep this enum in step with the server's allowed set.
 *   <li><b>{@link #supportsOilAdvisor()} is the single place the car-only rule is written.</b> Motor
 *       oil for a motorcycle is a different product (wet clutch, JASO MA rather than ACEA), and a
 *       truck's is different again — a car recommendation shown for either would be confidently
 *       wrong, which is the one failure mode the oil advisor is built to avoid. Maintenance
 *       <em>types</em> stay common to all vehicles on purpose; this is only about the oil advice.
 * </ul>
 *
 * <p>The declaration order is the order groups appear on the Автопарк tab and the order the
 * pickers list them, so it is deliberately "most common first" rather than alphabetical.
 */
public enum VehicleType {

    CAR("car", R.string.vt_car, R.string.vt_group_car,
            R.drawable.ic_car, R.drawable.car_placeholder),
    MOTORCYCLE("motorcycle", R.string.vt_motorcycle, R.string.vt_group_motorcycle,
            R.drawable.ic_motorcycle, R.drawable.motorcycle_placeholder),
    VAN("van", R.string.vt_van, R.string.vt_group_van,
            R.drawable.ic_van, R.drawable.van_placeholder),
    TRUCK("truck", R.string.vt_truck, R.string.vt_group_truck,
            R.drawable.ic_truck, R.drawable.truck_placeholder);

    /** What travels in JSON. Lowercase, stable — never send a label. */
    public final String apiValue;

    /** Singular label, for pickers and the detail screen's spec row. */
    public final int labelRes;

    /** Plural label, for the Автопарк section headers. */
    public final int groupLabelRes;

    /** Small glyph for headers, spec rows and section chips — tinted at the use site. */
    public final int iconRes;

    /**
     * Fallback thumbnail for a vehicle of this type with no photo. Distinct from {@link #iconRes}:
     * this one is a full-bleed image that replaces a photo, so it carries its own white ground and
     * must never be tinted by the ImageView showing it.
     */
    public final int placeholderRes;

    /** What an absent, empty or unrecognised value means. See the class comment. */
    public static final VehicleType DEFAULT = CAR;

    VehicleType(String apiValue, int labelRes, int groupLabelRes, int iconRes, int placeholderRes) {
        this.apiValue = apiValue;
        this.labelRes = labelRes;
        this.groupLabelRes = groupLabelRes;
        this.iconRes = iconRes;
        this.placeholderRes = placeholderRes;
    }

    /**
     * Parses a wire value. Tolerates the obvious synonyms so a hand-seeded row or a slightly
     * different server vocabulary doesn't silently land in the CAR bucket.
     */
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

    /** The enum entry at a picker position, clamped so a bad index can't throw. */
    public static VehicleType at(int index) {
        VehicleType[] all = values();
        return index < 0 || index >= all.length ? DEFAULT : all[index];
    }

    /**
     * Singular labels in declaration order — the array both the add-vehicle dropdown and the
     * edit-vehicle spinner are built from, so a position always means the same type on both screens.
     * Built here rather than declared as a {@code string-array} precisely so the enum stays the one
     * source of truth for that ordering.
     */
    public static String[] labels(Context ctx) {
        VehicleType[] all = values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            out[i] = ctx.getString(all[i].labelRes);
        }
        return out;
    }

    /** Whether the oil advisor may answer for this type. See the class comment. */
    public boolean supportsOilAdvisor() {
        return this == CAR;
    }

    /**
     * Whether a Bulgarian e-vignette applies to this type at all.
     *
     * <p><b>Motorcycles are exempt by law</b> (category L pays no toll on the national road network),
     * so asking the authority about one is not merely wasteful — its perfectly correct „no vignette
     * on this plate“ answer would render as an OVERDUE document and badge the bike red forever, with
     * nothing the owner could ever do about it. Exempt vehicles therefore skip the check entirely
     * and the card says „не се изисква“ instead of guessing. Same class of rule as
     * {@code ComplianceStatus} treating a failed check as unknown rather than as "expired": the app
     * must not manufacture a violation.
     *
     * <p><b>Trucks are deliberately left as "required" even though it isn't always true.</b> A goods
     * vehicle over 3.5 t pays the distance-based ТОЛ rather than a vignette, but the cutoff is a
     * weight this app doesn't store, so the type alone can't decide it. Reporting a vignette state
     * for a тол-liable truck is the lesser error — the answer is at least the authority's own, and it
     * is visibly about the vignette. Fixing it properly means asking for the weight, not guessing
     * from „камион“.
     */
    public boolean requiresVignette() {
        return this != MOTORCYCLE;
    }
}
