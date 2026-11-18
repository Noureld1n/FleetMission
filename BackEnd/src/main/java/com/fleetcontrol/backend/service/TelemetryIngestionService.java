package com.fleetcontrol.backend.service;

import com.fleetcontrol.backend.config.AppProperties;
import com.fleetcontrol.backend.dto.request.TelemetryBatchRequest;
import com.fleetcontrol.backend.dto.response.TelemetryAcceptedResponse;
import com.fleetcontrol.backend.exception.ApiException;
import com.fleetcontrol.backend.model.QueuedTelemetryBatch;
import com.fleetcontrol.backend.model.TelemetryVehicle;
import com.fleetcontrol.backend.model.VehicleStatus;
import com.fleetcontrol.backend.repository.TelemetryLiveRepository;
import com.fleetcontrol.backend.security.TenantIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.*;

@Service
public class TelemetryIngestionService {
    private final AppProperties properties;
    private final VehicleOwnershipCache ownershipCache;
    private final TelemetryLiveRepository liveState;
    private final TelemetryPersistencePipeline pipeline;
    private final MeterRegistry meters;
    public TelemetryIngestionService(AppProperties properties,VehicleOwnershipCache ownershipCache,
            TelemetryLiveRepository liveState,TelemetryPersistencePipeline pipeline,MeterRegistry meters) {
        this.properties=properties;this.ownershipCache=ownershipCache;this.liveState=liveState;this.pipeline=pipeline;this.meters=meters;
    }

    public Mono<TelemetryAcceptedResponse> accept(TelemetryBatchRequest request) {
        meters.counter("ingest.events.received").increment(request.events().size());
        if(request.events().size()>properties.ingestion().batchSize())
            return reject(HttpStatus.BAD_REQUEST,"BATCH_TOO_LARGE","A telemetry request cannot exceed the configured persistence batch size.",request.events().size());
        List<TelemetryBatchRequest.TelemetryEvent> normalized=new ArrayList<>(request.events().size());
        for(var event:request.events()) {
            String canonicalStatus;
            try { canonicalStatus=DomainSupport.enumValue(VehicleStatus.class,event.status(),"VEHICLE_STATUS"); }
            catch(ApiException invalid) { return reject(invalid.status().is4xxClientError()?HttpStatus.BAD_REQUEST:HttpStatus.INTERNAL_SERVER_ERROR,invalid.code(),invalid.getMessage(),request.events().size()); }
            if(!Double.isFinite(event.lat())||!Double.isFinite(event.lon())||event.lat() < -90||event.lat()>90||event.lon() < -180||event.lon()>180
                    ||(event.headingDeg()!=null&&(!Float.isFinite(event.headingDeg())||event.headingDeg()<0||event.headingDeg()>=360))
                    ||(event.speedKmh()!=null&&(!Float.isFinite(event.speedKmh())||event.speedKmh()<0)))
                return reject(HttpStatus.BAD_REQUEST,"INVALID_TELEMETRY","Telemetry contains an out-of-range numeric value.",request.events().size());
            normalized.add(new TelemetryBatchRequest.TelemetryEvent(event.vehicleId(),event.ts(),event.lat(),event.lon(),
                    event.headingDeg(),event.speedKmh(),event.batteryPct(),canonicalStatus));
        }
        return DomainSupport.identity().flatMap(identity->ownershipCache.activeVehicles(identity)
                .flatMap(known->{
                    Set<UUID> requested=new HashSet<>(); normalized.forEach(event->requested.add(event.vehicleId()));
                    if(!known.containsAll(requested)) {
                        return Mono.error(new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,"UNKNOWN_VEHICLE","One or more vehicle IDs are not active in this tenant."));
                    }
                    TelemetryPersistencePipeline.Reservation reservation=pipeline.reserve(normalized.size());
                    return Flux.fromIterable(normalized).concatMap(event->liveState.update(identity,event).map(TelemetryVehicle::seq))
                            .collectList().flatMap(sequences->{
                                pipeline.offer(reservation,new QueuedTelemetryBatch(identity,normalized,sequences));
                                return Mono.just(new TelemetryAcceptedResponse("accepted",normalized.size()));
                            }).doFinally(signal->reservation.releaseIfUnqueued());
                }))
                .onErrorMap(error->error instanceof ApiException?error:
                        new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"INGEST_DEPENDENCY_UNAVAILABLE","Database or Redis is unavailable; retry the telemetry batch."))
                .doOnError(error->meters.counter("ingest.events.rejected").increment(request.events().size()));
    }
    private <T> Mono<T> reject(HttpStatus status,String code,String detail,int count) {
        meters.counter("ingest.events.rejected").increment(count);
        return Mono.error(new ApiException(status,code,detail));
    }
}
