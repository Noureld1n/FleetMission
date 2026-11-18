package com.fleetcontrol.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateDistrictRequest(@NotBlank @Size(max=120) String name, @NotNull @Valid Polygon boundary) {
    public record Polygon(@NotBlank String type, @NotNull @Size(min=1) List<@NotNull @Valid List<@NotNull @Valid Position>> coordinates) {}
    public record Position(@NotNull Double longitude, @NotNull Double latitude) {}
}
