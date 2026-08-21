package com.example.maintenanceapp.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.maintenanceapp.R;
import com.example.maintenanceapp.model.Vehicle;
import com.example.maintenanceapp.util.ComplianceStatus;
import com.example.maintenanceapp.util.MaintenanceStatus;
import com.example.maintenanceapp.util.VehicleImages;
import com.example.maintenanceapp.util.VehicleType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VehicleAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** Callback for when a vehicle row is tapped. */
    public interface OnVehicleClickListener {
        void onVehicleClick(Vehicle vehicle);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_VEHICLE = 1;

    /**
     * One rendered line: either a group header or a vehicle. Built by {@link #rebuildRows()} from
     * the vehicle list, so the grouping lives in one place instead of being spread across position
     * arithmetic in every override.
     */
    private static final class Row {
        final VehicleType header;   // non-null on a header row
        final int headerCount;      // vehicles under that header
        final Vehicle vehicle;      // non-null on a vehicle row

        Row(VehicleType header, int headerCount) {
            this.header = header;
            this.headerCount = headerCount;
            this.vehicle = null;
        }

        Row(Vehicle vehicle) {
            this.header = null;
            this.headerCount = 0;
            this.vehicle = vehicle;
        }
    }

    private final List<Vehicle> vehicles;
    private final List<Row> rows = new ArrayList<>();
    private final OnVehicleClickListener clickListener;

    // Per-vehicle service status (vehicle id -> worst status), populated asynchronously from
    // GET /vehicles/maintenance. A missing entry means "unknown" and shows no badge.
    private final Map<String, MaintenanceStatus> statuses = new HashMap<>();

    // Per-vehicle document status (vehicle id -> worst of винетка/ГТП/ГО). Kept in its own map
    // rather than merged into `statuses` because the two badges answer different questions and are
    // populated from different sources at different times.
    private final Map<String, ComplianceStatus> docStatuses = new HashMap<>();

    public VehicleAdapter(List<Vehicle> vehicles, OnVehicleClickListener clickListener) {
        this.vehicles = new ArrayList<>(vehicles);
        this.clickListener = clickListener;
        rebuildRows();
    }

    /** Replaces the list contents and refreshes the view (used when data arrives from the server). */
    public void setVehicles(List<Vehicle> newVehicles) {
        vehicles.clear();
        vehicles.addAll(newVehicles);
        rebuildRows();
        notifyDataSetChanged();
    }

    /**
     * Groups the fleet by {@link VehicleType} — in the enum's declaration order, so sections don't
     * reshuffle as vehicles come and go — and inserts a header above each group.
     *
     * <p><b>Headers only appear once the fleet holds more than one type.</b> A single band reading
     * "Автомобили" over a list that is entirely cars separates nothing and costs a line; the
     * grouping exists to tell kinds apart, so with one kind there is nothing to tell apart.
     */
    private void rebuildRows() {
        rows.clear();
        // Bucket first: the enum order decides section order, while the order within a section is
        // the order the list arrived in (whatever GET /vehicles returned).
        Map<VehicleType, List<Vehicle>> buckets = new HashMap<>();
        for (Vehicle v : vehicles) {
            VehicleType type = VehicleType.of(v);
            List<Vehicle> bucket = buckets.get(type);
            if (bucket == null) {
                bucket = new ArrayList<>();
                buckets.put(type, bucket);
            }
            bucket.add(v);
        }
        boolean withHeaders = buckets.size() > 1;
        for (VehicleType type : VehicleType.values()) {
            List<Vehicle> bucket = buckets.get(type);
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            if (withHeaders) {
                rows.add(new Row(type, bucket.size()));
            }
            for (Vehicle v : bucket) {
                rows.add(new Row(v));
            }
        }
    }

    /** Updates the per-vehicle service-status badges (id -> status) and refreshes the rows. */
    public void setStatuses(Map<String, MaintenanceStatus> newStatuses) {
        statuses.clear();
        if (newStatuses != null) {
            statuses.putAll(newStatuses);
        }
        notifyDataSetChanged();
    }

    /**
     * Updates the per-vehicle document badges (id -> worst document status) and refreshes the rows.
     * A missing entry, or {@link ComplianceStatus#OK}, shows no badge — see the layout comment for
     * why there is no positive state.
     */
    public void setDocStatuses(Map<String, ComplianceStatus> newStatuses) {
        docStatuses.clear();
        if (newStatuses != null) {
            docStatuses.putAll(newStatuses);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).header != null ? TYPE_HEADER : TYPE_VEHICLE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(
                    inflater.inflate(R.layout.item_vehicle_group_header, parent, false));
        }
        return new ViewHolder(inflater.inflate(R.layout.item_vehicle, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder rawHolder, int position) {
        Row row = rows.get(position);

        if (row.header != null) {
            HeaderHolder header = (HeaderHolder) rawHolder;
            header.icon.setImageResource(row.header.iconRes);
            header.label.setText(row.header.groupLabelRes);
            header.count.setText(String.valueOf(row.headerCount));
            return;
        }

        ViewHolder holder = (ViewHolder) rawHolder;
        Vehicle vehicle = row.vehicle;
        holder.txtMake.setText(vehicle.make);
        holder.txtModel.setText(vehicle.model);

        // Prefer the inline base64 photo, fall back to a bundled drawable by name, then this
        // vehicle type's own placeholder. Rows never fetch per-id, so a photo only shows here if
        // GET /vehicles included it. Cached by vehicle id so scrolling doesn't re-decode per bind.
        VehicleImages.apply(holder.itemView.getContext(), holder.imgVehicle,
                vehicle.imageBase64, vehicle.imageName, vehicle.id, VehicleType.of(vehicle));

        // Service reminder badge — tinted per status, hidden when the status is unknown.
        MaintenanceStatus status = vehicle.id == null ? null : statuses.get(vehicle.id);
        if (status == null) {
            holder.txtStatus.setVisibility(View.GONE);
        } else {
            int color = ContextCompat.getColor(holder.itemView.getContext(), status.colorRes);
            holder.txtStatus.getBackground().mutate().setTint(color);
            holder.txtStatus.setText(status.labelRes);
            holder.txtStatus.setVisibility(View.VISIBLE);
        }

        // Document badge — only for DUE/OVERDUE. OK and unknown both render as nothing, on purpose:
        // two of the three dates are user-declared, so a reassuring badge would overstate what the
        // app actually knows.
        ComplianceStatus docStatus = vehicle.id == null ? null : docStatuses.get(vehicle.id);
        if (docStatus == null || docStatus == ComplianceStatus.OK) {
            holder.txtDocStatus.setVisibility(View.GONE);
        } else {
            int docColor = ContextCompat.getColor(holder.itemView.getContext(), docStatus.colorRes);
            holder.txtDocStatus.getBackground().mutate().setTint(docColor);
            holder.txtDocStatus.setText(docStatus.labelRes);
            holder.txtDocStatus.setVisibility(View.VISIBLE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onVehicleClick(vehicle);
            }
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView txtMake, txtModel, txtStatus, txtDocStatus;
        ImageView imgVehicle;

        ViewHolder(View itemView) {
            super(itemView);
            txtMake = itemView.findViewById(R.id.txtMake);
            txtModel = itemView.findViewById(R.id.txtModel);
            txtStatus = itemView.findViewById(R.id.txtVehicleStatus);
            txtDocStatus = itemView.findViewById(R.id.txtVehicleDocStatus);
            imgVehicle = itemView.findViewById(R.id.imgVehicle);
        }
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView label, count;

        HeaderHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.groupIcon);
            label = itemView.findViewById(R.id.groupLabel);
            count = itemView.findViewById(R.id.groupCount);
        }
    }
}
