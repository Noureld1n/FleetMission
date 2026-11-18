package com.fleetcontrol.backend.model;

public enum AlertSeverity { INFO, WARNING, CRITICAL;
    public String value() { return name().toLowerCase(); }
}
