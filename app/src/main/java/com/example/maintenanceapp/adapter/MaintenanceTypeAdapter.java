package com.example.maintenanceapp.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.maintenanceapp.R;
import com.example.maintenanceapp.model.MaintenanceType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Renders the service-type catalog as a 2-column grid on the Поддръжка tab. Each card shows the
 * type name and expands on tap to reveal its recommended interval.
 */
public class MaintenanceTypeAdapter extends RecyclerView.Adapter<MaintenanceTypeAdapter.ViewHolder> {

    private final List<MaintenanceType> items = new ArrayList<>();
    private final Set<Integer> expanded = new HashSet<>();
    private final Set<Integer> warningShown = new HashSet<>();

    public void setTypes(List<MaintenanceType> types) {
        items.clear();
        items.addAll(types);
        expanded.clear();
        warningShown.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_maintenance_type, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MaintenanceType type = items.get(position);
        Context ctx = holder.itemView.getContext();

        holder.txtName.setText(type.name);
        if (type.defaultIntervalKm > 0) {
            holder.txtInterval.setText(ctx.getString(R.string.maint_interval, formatKm(type.defaultIntervalKm)));
        } else {
            holder.txtInterval.setText(R.string.maint_interval_unknown);
        }

        boolean isExpanded = expanded.contains(position);
        holder.detail.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        holder.chevron.setRotation(isExpanded ? 180f : 0f);

        // Warning + info button only when this type carries a description.
        boolean hasWarning = type.description != null && !type.description.trim().isEmpty();
        holder.info.setVisibility(hasWarning ? View.VISIBLE : View.GONE);
        holder.txtWarning.setText(hasWarning ? type.description.trim() : "");
        holder.warning.setVisibility(hasWarning && warningShown.contains(position) ? View.VISIBLE : View.GONE);

        // Full re-layout on toggle: GridLayoutManager caches row heights and a single
        // notifyItemChanged grows a card but won't shrink the row back on collapse.
        // notifyDataSetChanged forces every row to re-measure (the catalog is tiny).
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (expanded.contains(pos)) expanded.remove(pos); else expanded.add(pos);
            notifyDataSetChanged();
        });

        holder.info.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (warningShown.contains(pos)) warningShown.remove(pos); else warningShown.add(pos);
            notifyDataSetChanged();
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static String formatKm(int km) {
        return String.format(Locale.US, "%,d", km).replace(',', ' ') + " км";
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView txtName, txtInterval, txtWarning;
        final ImageView chevron, info;
        final View detail, warning;

        ViewHolder(View itemView) {
            super(itemView);
            txtName = itemView.findViewById(R.id.txtTypeName);
            txtInterval = itemView.findViewById(R.id.txtTypeInterval);
            txtWarning = itemView.findViewById(R.id.txtWarning);
            chevron = itemView.findViewById(R.id.imgChevron);
            info = itemView.findViewById(R.id.imgInfo);
            detail = itemView.findViewById(R.id.detailSection);
            warning = itemView.findViewById(R.id.warningSection);
        }
    }
}
