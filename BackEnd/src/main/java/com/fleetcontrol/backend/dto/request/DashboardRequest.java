package com.fleetcontrol.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record DashboardRequest(@NotBlank @Size(max=120) String name, boolean shared,
                               @NotNull @Size(max=100) List<@NotNull @Valid Widget> widgets) {
    public record Widget(@NotBlank String type, @NotNull Map<String,Object> config,
                         @PositiveOrZero int gridX, @PositiveOrZero int gridY,
                         @Positive int gridW, @Positive int gridH) {}
}
