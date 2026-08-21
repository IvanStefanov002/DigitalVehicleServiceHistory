package com.example.maintenanceapp.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * One serviceable part/interval for a vehicle (e.g. "Brake discs", "Oil &amp; oil filter").
 * Mileage figures are in km. Populated from the /vehicles/maintenance API response.
 */
public class MaintenanceItem implements Serializable {
    /**
     * Server-side primary key of the underlying maintenance record, used to delete it. Empty when
     * the payload doesn't carry one (older server builds) — callers must treat an empty id as
     * "not deletable" rather than sending a request that can't work.
     */
    public String id;

    /**
     * Id of the service type this record belongs to. Sent by
     * {@code GET /vehicles/maintenance/history} so records can be grouped by type without matching
     * on the display name (which changes with a rename). Empty from the latest-per-type endpoint,
     * which doesn't send it.
     */
    public String typeId;

    public String name;             // e.g. "Brake discs"
    public int lastChangeMileage;   // km at last service (0 if unknown)
    public int nextChangeMileage;   // km recommended for next service (0 if unknown)
    public String lastChangeDate;   // optional, free text (may be null/empty)
    public String notes;            // optional (may be null/empty)

    /**
     * What the service cost, in the app's single currency. <b>Negative means "not recorded"</b> —
     * a record entered before the field existed, or one the user left blank — which is what keeps
     * it distinguishable from a genuine 0. Display sites must check {@code >= 0} before showing it.
     */
    public double cost = -1;

    /**
     * Id of the document photo attached to this record (a receipt / invoice / protocol), or empty when
     * there is none. Both list endpoints send it; <b>neither sends the bytes</b> — a vehicle's history
     * is tens of records, so embedding images would multiply one photo by the whole history in a single
     * response. The image is fetched one at a time by
     * {@code util/MaintenanceDocuments} when the user opens it.
     *
     * <p>Empty means "no document" and is also what an older server build produces, so display sites
     * just hide the affordance rather than special-casing anything.
     */
    public String documentId = "";

    /**
     * Parses the {@code GET /vehicles/maintenance} body ({@code {"items":[...]}}) into a list.
     * Shared by the vehicle detail screen and the Автопарк reminder badges so both read the
     * payload identically. Returns an empty list when there are no records.
     */
    public static List<MaintenanceItem> listFromJson(String body) throws JSONException {
        List<MaintenanceItem> list = new ArrayList<>();
        JSONArray arr = new JSONObject(body).optJSONArray("items");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                MaintenanceItem m = new MaintenanceItem();
                // optString also copes with the server sending id as a JSON number.
                m.id = o.optString("id", "");
                m.typeId = o.optString("typeId", "");
                m.name = o.optString("name", "");
                m.lastChangeMileage = o.optInt("lastChangeMileage", 0);
                m.nextChangeMileage = o.optInt("nextChangeMileage", 0);
                m.lastChangeDate = o.optString("lastChangeDate", "");
                m.notes = o.optString("notes", "");
                // -1 for both "absent" and "null": the server omits the key on records with no
                // recorded price, and optDouble's fallback doesn't cover a JSON null.
                m.cost = o.isNull("cost") ? -1 : o.optDouble("cost", -1);
                // optString also copes with the server sending it as a JSON number; isNull covers an
                // explicit null, which optString would otherwise render as the string "null".
                m.documentId = o.isNull("documentId") ? "" : o.optString("documentId", "");
                list.add(m);
            }
        }
        return list;
    }
}
