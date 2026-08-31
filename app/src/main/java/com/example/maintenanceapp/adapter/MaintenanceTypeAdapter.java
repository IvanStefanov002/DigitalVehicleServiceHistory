/*
 * MaintenanceTypeAdapter.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

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
import com.example.maintenanceapp.util.MaintenanceTypeEditor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** used in tab "Поддръжка" to show different cards for maintenance types and their intervals */
public class MaintenanceTypeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnEditListener {
        void onEditType(MaintenanceType type);
    }

    public interface OnHideListener {
        void onHideType(MaintenanceType type);
    }

    public interface OnAddListener {
        void onAddType();
    }

    private static final int VIEW_TYPE_ITEM = 0;
    private static final int VIEW_TYPE_ADD = 1;

    private final List<MaintenanceType> items = new ArrayList<>();
    private final Set<Integer> expanded = new HashSet<>();
    private final Set<Integer> warningShown = new HashSet<>();
    private OnEditListener editListener;
    private OnHideListener hideListener;
    private OnAddListener addListener;

    public void setOnEditListener(OnEditListener listener) {
        this.editListener = listener;
    }

    public void setOnHideListener(OnHideListener listener) {
        this.hideListener = listener;
    }

    public void setOnAddListener(OnAddListener listener) {
        this.addListener = listener;
    }

    public void setTypes(List<MaintenanceType> types) {
        items.clear();
        items.addAll(types);
        expanded.clear();
        warningShown.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return position == items.size() ? VIEW_TYPE_ADD : VIEW_TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_ADD) {
            return new AddViewHolder(
                    inflater.inflate(R.layout.item_maintenance_type_add, parent, false));
        }
        return new ViewHolder(inflater.inflate(R.layout.item_maintenance_type, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder rawHolder, int position) {
        if (rawHolder instanceof AddViewHolder) {
            rawHolder.itemView.setOnClickListener(v -> {
                if (addListener != null) {
                    addListener.onAddType();
                }
            });
            return;
        }

        ViewHolder holder = (ViewHolder) rawHolder;
        MaintenanceType type = items.get(position);
        Context ctx = holder.itemView.getContext();

        holder.txtName.setText(type.name);
        holder.custom.setVisibility(type.custom ? View.VISIBLE : View.GONE);

        if (type.hasInterval()) {
            holder.txtInterval.setText(ctx.getString(R.string.maint_interval,
                    MaintenanceTypeEditor.intervalLabel(ctx.getResources(),
                            type.defaultIntervalKm, type.defaultIntervalMonths)));
        } else {
            holder.txtInterval.setText(R.string.maint_interval_unknown);
        }

        boolean showSuggestion = type.overridden();
        holder.txtSuggestion.setVisibility(showSuggestion ? View.VISIBLE : View.GONE);
        if (showSuggestion) {
            holder.txtSuggestion.setText(ctx.getString(R.string.mt_suggested,
                    MaintenanceTypeEditor.intervalLabel(ctx.getResources(),
                            type.suggestedIntervalKm, type.suggestedIntervalMonths)));
        }

        boolean editable = type.id != null && !type.id.isEmpty();
        holder.actions.setVisibility(editable ? View.VISIBLE : View.GONE);

        boolean isExpanded = expanded.contains(position);
        holder.detail.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        holder.chevron.setRotation(isExpanded ? 180f : 0f);

        // Warning + info button only when this type carries a description.
        boolean hasWarning = type.description != null && !type.description.trim().isEmpty();
        holder.info.setVisibility(hasWarning ? View.VISIBLE : View.GONE);
        holder.txtWarning.setText(hasWarning ? type.description.trim() : "");
        holder.warning.setVisibility(hasWarning && warningShown.contains(position) ? View.VISIBLE : View.GONE);

        // re-layout on toggle of single card
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

        holder.btnEdit.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || pos >= items.size() || editListener == null) return;
            editListener.onEditType(items.get(pos));
        });

        holder.btnHide.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || pos >= items.size() || hideListener == null) return;
            hideListener.onHideType(items.get(pos));
        });
    }

    @Override
    public int getItemCount() { return items.size() + 1; }

    static class AddViewHolder extends RecyclerView.ViewHolder {
        AddViewHolder(View itemView) {
            super(itemView);
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView txtName, txtInterval, txtSuggestion, txtWarning, custom;
        final ImageView chevron, info;
        final View detail, warning, btnEdit, btnHide, actions;

        ViewHolder(View itemView) {
            super(itemView);
            txtName = itemView.findViewById(R.id.txtTypeName);
            txtInterval = itemView.findViewById(R.id.txtTypeInterval);
            txtSuggestion = itemView.findViewById(R.id.txtTypeSuggestion);
            txtWarning = itemView.findViewById(R.id.txtWarning);
            custom = itemView.findViewById(R.id.txtTypeCustom);
            chevron = itemView.findViewById(R.id.imgChevron);
            info = itemView.findViewById(R.id.imgInfo);
            detail = itemView.findViewById(R.id.detailSection);
            warning = itemView.findViewById(R.id.warningSection);
            btnEdit = itemView.findViewById(R.id.btnEditType);
            btnHide = itemView.findViewById(R.id.btnHideType);
            actions = itemView.findViewById(R.id.typeActions);
        }
    }
}
