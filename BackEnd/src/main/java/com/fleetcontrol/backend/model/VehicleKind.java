package com.fleetcontrol.backend.model;

public enum VehicleKind { DRONE, SCOOTER, EV_CHARGER;
    public String value() { return name().toLowerCase(); }
}
