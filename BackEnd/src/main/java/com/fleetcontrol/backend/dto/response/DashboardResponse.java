package com.fleetcontrol.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DashboardResponse(UUID id, UUID ownerId, String name, boolean shared,
                                OffsetDateTime createdAt, OffsetDateTime updatedAt, List<Widget> widgets) {
    public DashboardResponse { widgets = List.copyOf(widgets); }
    public record Widget(UUID id, String type, Map<String,Object> config, int gridX, int gridY, int gridW, int gridH) {}
}
