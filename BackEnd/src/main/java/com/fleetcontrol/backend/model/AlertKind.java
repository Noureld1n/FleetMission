package com.fleetcontrol.backend.model;

public enum AlertKind { LOW_BATTERY, VEHICLE_OFFLINE, DISTRICT_OUTAGE;
    public String value() { return name().toLowerCase(); }
}
