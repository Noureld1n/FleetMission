package com.fleetcontrol.backend.model;

import java.time.OffsetDateTime;

public record FleetMinuteAggregate(OffsetDateTime bucket, Double batteryAverage, Integer batteryMinimum,
                                   Long sampleCount) {}
