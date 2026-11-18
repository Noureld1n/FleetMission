package com.fleetcontrol.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record TelemetryBatchRequest(@NotEmpty @Size(max=500) List<@NotNull @Valid TelemetryEvent> events) {
    public record TelemetryEvent(
            @NotNull UUID vehicleId,
            @NotNull java.time.Instant ts,
            @jakarta.validation.constraints.DecimalMin("-90.0") @jakarta.validation.constraints.DecimalMax("90.0") double lat,
            @jakarta.validation.constraints.DecimalMin("-180.0") @jakarta.validation.constraints.DecimalMax("180.0") double lon,
            @jakarta.validation.constraints.DecimalMin("0.0") @jakarta.validation.constraints.DecimalMax(value="360.0",inclusive=false) Float headingDeg,
            @jakarta.validation.constraints.DecimalMin("0.0") Float speedKmh,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) int batteryPct,
            @NotNull String status
    ) {}
}
