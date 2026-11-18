package com.fleetcontrol.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record VehicleResponse(UUID id, String externalRef, String kind, UUID homeDistrictId, String status,
                              OffsetDateTime createdAt, OffsetDateTime retiredAt, Map<String,Object> latestState) {}
