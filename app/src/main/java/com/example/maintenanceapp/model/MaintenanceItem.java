package com.example.maintenanceapp.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class MaintenanceItem implements Serializable {

    public String id;
    public String typeId;

    public String name;             // e.g. "Brake discs"
    public int lastChangeMileage;   // km at last service (0 if unknown)
    public int nextChangeMileage;   // km recommended for next service (0 if unknown)
    public String lastChangeDate;   // optional, free text (may be null/empty)
    public String notes;            // optional (may be null/empty)
    public double cost = -1;
    public String documentId = "";

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
