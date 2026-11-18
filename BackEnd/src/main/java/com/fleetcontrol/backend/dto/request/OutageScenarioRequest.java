package com.fleetcontrol.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record OutageScenarioRequest(
        @NotNull UUID districtId,
        @Min(10) @Max(3600) Integer durationSeconds
) {
    public int effectiveDurationSeconds() { return durationSeconds == null ? 120 : durationSeconds; }
}
