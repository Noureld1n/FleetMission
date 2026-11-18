package com.fleetcontrol.backend.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record VehicleMinuteAggregate(UUID vehicleId, OffsetDateTime bucket, Double batteryAverage,
                                     Integer batteryMinimum, Double speedAverage, Double speedMaximum,
                                     Long sampleCount) {}
