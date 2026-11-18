package com.fleetcontrol.backend.dto.response;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record VehicleHistoryResponse(UUID vehicleId, Instant from, Instant to, List<Point> points) {
    public VehicleHistoryResponse { points=List.copyOf(points); }
    public record Point(OffsetDateTime bucket,Double batteryAverage,Integer batteryMinimum,
                        Double speedAverage,Double speedMaximum,Long sampleCount) {}
}
