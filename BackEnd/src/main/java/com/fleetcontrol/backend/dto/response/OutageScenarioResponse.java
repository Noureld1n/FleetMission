package com.fleetcontrol.backend.dto.response;

import java.util.UUID;

public record OutageScenarioResponse(UUID commandId, UUID districtId, int affectedVehicles, AlertResponse alert) {}
