package com.fleetcontrol.backend.model;

import java.time.Instant;
import java.util.UUID;

public record TelemetryVehicle(UUID vehicleId, Instant ts, long seq, double lat, double lon,
                               Float headingDeg, Float speedKmh, int batteryPct, String status) {}
