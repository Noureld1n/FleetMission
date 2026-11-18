package com.fleetcontrol.backend.model;

public enum WidgetType { MAP, LIVE_CHART, STATUS_TILES, ALERT_LIST;
    public String value() { return name().toLowerCase(); }
}
