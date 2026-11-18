package com.fleetcontrol.backend.dto.response;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

public record FleetMetricsResponse(Instant from,Instant to,List<Point> points) {
    public FleetMetricsResponse { points=List.copyOf(points); }
    public record Point(OffsetDateTime bucket,Double batteryAverage,Integer batteryMinimum,Long sampleCount) {}
}
