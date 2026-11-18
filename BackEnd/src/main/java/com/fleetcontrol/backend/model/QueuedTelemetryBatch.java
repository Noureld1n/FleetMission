package com.fleetcontrol.backend.model;

import com.fleetcontrol.backend.dto.request.TelemetryBatchRequest;
import com.fleetcontrol.backend.security.TenantIdentity;
import java.util.List;

/** One atomically queued request; tenant comes only from authenticated API-key identity. */
public record QueuedTelemetryBatch(TenantIdentity identity, List<TelemetryBatchRequest.TelemetryEvent> events, List<Long> sequences) {
    public QueuedTelemetryBatch { events=List.copyOf(events); sequences=List.copyOf(sequences); }
}
