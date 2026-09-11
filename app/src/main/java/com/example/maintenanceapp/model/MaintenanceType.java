/*
 * MaintenanceType.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

package com.example.maintenanceapp.model;

import java.io.Serializable;

public class MaintenanceType implements Serializable {

    public String id = "";
    public String name;
    public int defaultIntervalKm;
    public int defaultIntervalMonths;
    public int suggestedIntervalKm;
    public int suggestedIntervalMonths;
    public boolean suggested;
    public boolean custom;
    public boolean archived;
    public String description;

    public MaintenanceType(String name, int defaultIntervalKm) {
        this(name, defaultIntervalKm, "");
    }

    public MaintenanceType(String name, int defaultIntervalKm, String description) {
        this.name = name;
        this.defaultIntervalKm = defaultIntervalKm;
        this.description = description == null ? "" : description;
    }

    public boolean tracksKm() {
        return defaultIntervalKm > 0;
    }

    public boolean tracksTime() {
        return defaultIntervalMonths > 0;
    }

    public boolean hasInterval() {
        return tracksKm() || tracksTime();
    }

    public boolean overridesKm() {
        return suggestedIntervalKm > 0 && defaultIntervalKm != suggestedIntervalKm;
    }

    public boolean overridesTime() {
        return suggestedIntervalMonths > 0 && defaultIntervalMonths != suggestedIntervalMonths;
    }

    public boolean overridden() {
        return overridesKm() || overridesTime();
    }
}
