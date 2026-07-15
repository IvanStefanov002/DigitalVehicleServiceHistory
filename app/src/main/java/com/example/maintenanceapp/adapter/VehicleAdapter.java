package com.example.maintenanceapp.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.maintenanceapp.R;
import com.example.maintenanceapp.model.Vehicle;

import java.util.List;

public class VehicleAdapter extends RecyclerView.Adapter<VehicleAdapter.ViewHolder> {

    private final List<Vehicle> vehicles;

    public VehicleAdapter(List<Vehicle> vehicles) {
        this.vehicles = vehicles;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_vehicle, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Vehicle vehicle = vehicles.get(position);
        holder.txtMake.setText(vehicle.make);
        holder.txtModel.setText(vehicle.model);
    }

    @Override
    public int getItemCount() {
        return vehicles.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView txtMake, txtModel;
        ImageView imgVehicle;

        ViewHolder(View itemView) {
            super(itemView);
            txtMake = itemView.findViewById(R.id.txtMake);
            txtModel = itemView.findViewById(R.id.txtModel);
            imgVehicle = itemView.findViewById(R.id.imgVehicle);
        }
    }
}