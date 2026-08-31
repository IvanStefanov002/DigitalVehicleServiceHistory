/*
 * Api.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

package com.example.maintenanceapp.util;

/** Every backend URL the app talks to, in one place. */

public final class Api {

    /** Server's public IPv4 address and port. */
    public static final String BASE = "http://92.5.55.85:27778";

    /** login & register */
    public static final String LOGIN = BASE + "/users/login";
    public static final String REGISTER = BASE + "/users/register";

    /** The token's whole fleet; sends no username, the owner comes from the Bearer token. */
    public static final String VEHICLES = BASE + "/vehicles";
    public static final String VEHICLE_ADD = BASE + "/vehicles/add";
    public static final String VEHICLE_UPDATE = BASE + "/vehicles/update";
    public static final String VEHICLE_DELETE = BASE + "/vehicles/delete";
    public static final String VEHICLE_IMAGE = BASE + "/vehicles/image";
    public static final String VEHICLE_VIGNETTE = BASE + "/vehicles/vignette";
    public static final String MAINTENANCE = BASE + "/vehicles/maintenance";
    public static final String MAINTENANCE_HISTORY = BASE + "/vehicles/maintenance/history";
    public static final String MAINTENANCE_ADD = BASE + "/vehicles/maintenance/add";
    public static final String MAINTENANCE_DELETE = BASE + "/vehicles/maintenance/delete";
    public static final String MAINTENANCE_DOCUMENT = BASE + "/vehicles/maintenance/document";
    public static final String SHARE = BASE + "/vehicles/share";
    public static final String SHARE_REVOKE = BASE + "/vehicles/share/revoke";

    /** maintenance */
    public static final String MAINTENANCE_TYPES = BASE + "/maintenance/types";
    public static final String MAINTENANCE_TYPE_CREATE = BASE + "/maintenance/types";
    public static final String MAINTENANCE_TYPE_UPDATE = BASE + "/maintenance/types/update";
    public static final String MAINTENANCE_TYPE_ARCHIVE = BASE + "/maintenance/types/archive";
    public static final String MAINTENANCE_TYPE_RESTORE = BASE + "/maintenance/types/restore";

    /** OIL */
    public static final String OIL_ENGINES = BASE + "/oil/engines";
    public static final String OIL_RECOMMEND = BASE + "/oil/recommend";

    private Api() { }
}
