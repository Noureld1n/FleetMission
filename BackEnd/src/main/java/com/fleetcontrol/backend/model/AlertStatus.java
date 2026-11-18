package com.fleetcontrol.backend.model;

public enum AlertStatus { OPEN, ACKNOWLEDGED, RESOLVED;
    public String value() { return name().toLowerCase(); }
}
