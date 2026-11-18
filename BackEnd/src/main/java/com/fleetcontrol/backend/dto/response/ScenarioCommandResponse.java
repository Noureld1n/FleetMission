package com.fleetcontrol.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ScenarioCommandResponse(UUID id, String type, Map<String,Object> payload,
                                      int attempt, OffsetDateTime expiresAt, OffsetDateTime leaseUntil) {}
