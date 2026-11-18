package com.fleetcontrol.backend.model;

public enum VehicleStatus { IDLE, ACTIVE, CHARGING, OFFLINE, FAULT;
    public String value() { return name().toLowerCase(); }
}
