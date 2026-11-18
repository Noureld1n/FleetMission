package com.fleetcontrol.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DistrictResponse(UUID id, String name, Object boundary, OffsetDateTime createdAt) {}
