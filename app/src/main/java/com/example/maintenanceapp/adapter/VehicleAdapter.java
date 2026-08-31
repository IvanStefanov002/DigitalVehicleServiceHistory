/*
 * VehicleAdapter.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

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

    /** listener for vehicle clicked */
    public interface OnVehicleClickListener {
        void onVehicleClick(Vehicle vehicle);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_VEHICLE = 1;

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

    // Per-vehicle service status
    private final Map<String, MaintenanceStatus> statuses = new HashMap<>();

    // Per-vehicle document status
    private final Map<String, ComplianceStatus> docStatuses = new HashMap<>();

    public VehicleAdapter( List<Vehicle> vehicles, OnVehicleClickListener clickListener ) {
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

    /** Groups the fleet by VehicleType */
    private void rebuildRows() {
        rows.clear();
        // Bucket first: the enum order decides section order, while the order within a section is
        // the order the list arrived in (whatever GET /vehicles returned).
        Map<VehicleType, List<Vehicle>> buckets = new HashMap<>();
        for (Vehicle v : vehicles) {
            VehicleType type = VehicleType.of(v);
            List<Vehicle> bucket = buckets.get(type);
            if ( bucket == null ) {
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

    public void setStatuses(Map<String, MaintenanceStatus> newStatuses) {
        statuses.clear();
        if (newStatuses != null) {
            statuses.putAll(newStatuses);
        }
        notifyDataSetChanged();
    }

    /** Updates the per-vehicle document badges */
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

        // Prefer the inline base64 photo, fall back to a bundled drawable by name, then this vehicle type's own placeholder
        VehicleImages.apply(holder.itemView.getContext(), holder.imgVehicle,
                vehicle.imageBase64, vehicle.imageName, vehicle.id, VehicleType.of(vehicle));

        // Service reminder badge
        MaintenanceStatus status = vehicle.id == null ? null : statuses.get(vehicle.id);
        if (status == null) {
            holder.txtStatus.setVisibility(View.GONE);
        } else {
            int color = ContextCompat.getColor(holder.itemView.getContext(), status.colorRes);
            holder.txtStatus.getBackground().mutate().setTint(color);
            holder.txtStatus.setText(status.labelRes);
            holder.txtStatus.setVisibility(View.VISIBLE);
        }

        // Document badge
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
