package com.fleetcontrol.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AlertResponse(UUID id, UUID vehicleId, UUID districtId, String kind, String severity,
                            String status, OffsetDateTime openedAt, OffsetDateTime acknowledgedAt,
                            OffsetDateTime resolvedAt, OffsetDateTime updatedAt, Map<String,Object> detail) {}
