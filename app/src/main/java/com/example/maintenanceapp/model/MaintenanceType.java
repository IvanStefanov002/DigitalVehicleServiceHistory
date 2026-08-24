package com.example.maintenanceapp.model;

public class MaintenanceType {
    public String name;
    public int defaultIntervalKm;   // 0 = no fixed interval
    public String description;      // optional warning/notes; empty = none

    public MaintenanceType(String name, int defaultIntervalKm) {
        this(name, defaultIntervalKm, "");
    }

    public MaintenanceType(String name, int defaultIntervalKm, String description) {
        this.name = name;
        this.defaultIntervalKm = defaultIntervalKm;
        this.description = description == null ? "" : description;
    }
}
