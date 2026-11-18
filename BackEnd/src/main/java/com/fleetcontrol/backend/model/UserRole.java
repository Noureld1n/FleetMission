package com.fleetcontrol.backend.model;

public enum UserRole { OPERATOR, VIEWER;
    public String value() { return name().toLowerCase(); }
}
