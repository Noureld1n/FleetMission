package com.fleetcontrol.backend.service;

import com.fleetcontrol.backend.dto.request.OutageScenarioRequest;
import com.fleetcontrol.backend.dto.response.AlertResponse;
import com.fleetcontrol.backend.dto.response.OutageScenarioResponse;
import com.fleetcontrol.backend.dto.response.ScenarioCommandResponse;
import com.fleetcontrol.backend.exception.ApiException;
import com.fleetcontrol.backend.model.TelemetryVehicle;
import com.fleetcontrol.backend.repository.DomainRepository;
import com.fleetcontrol.backend.repository.ScenarioRepository;
import com.fleetcontrol.backend.repository.TelemetryLiveRepository;
import com.fleetcontrol.backend.repository.TelemetryRepository;
import com.fleetcontrol.backend.security.TenantIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class ScenarioService {
    private static final Logger log=LoggerFactory.getLogger(ScenarioService.class);
    private final ScenarioRepository scenarios;
    private final DomainRepository domain;
    private final TelemetryRepository telemetry;
    private final TelemetryLiveRepository live;
    private final TenantTransaction transactions;
    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;
    private final MeterRegistry meters;

    public ScenarioService(ScenarioRepository scenarios,DomainRepository domain,TelemetryRepository telemetry,
            TelemetryLiveRepository live,TenantTransaction transactions,ReactiveStringRedisTemplate redis,
            Clock clock,MeterRegistry meters) {
        this.scenarios=scenarios;this.domain=domain;this.telemetry=telemetry;this.live=live;
        this.transactions=transactions;this.redis=redis;this.clock=clock;this.meters=meters;
    }

    public Mono<OutageScenarioResponse> triggerOutage(OutageScenarioRequest request) {
        return DomainSupport.identity().flatMap(identity->{
            OffsetDateTime recentSince=OffsetDateTime.ofInstant(clock.instant().minusSeconds(300),ZoneOffset.UTC);
            Map<String,Object> payload=Map.of("districtId",request.districtId(),"action","outage",
                    "durationSeconds",request.effectiveDurationSeconds());
            Mono<PreparedOutage> prepared=transactions.inTenantTransaction(()->scenarios.districtName(identity.tenantId(),request.districtId())
                    .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND,"DISTRICT_NOT_FOUND","District was not found.")))
                    .flatMap(districtName->scenarios.vehiclesInDistrict(identity.tenantId(),request.districtId(),recentSince).collectList()
                            .flatMap(vehicles->scenarios.createOutageCommand(identity.tenantId(),request.districtId(),payload)
                                    .flatMap(commandId->openOutageAlerts(identity,request,districtName,commandId,vehicles)
                                            .flatMap(alerts->telemetry.markVehiclesFault(identity.tenantId(),vehicles.stream().map(TelemetryVehicle::vehicleId).toList())
                                                    .thenReturn(new PreparedOutage(commandId,vehicles,alerts)))))));
            return prepared.flatMap(result->publishOutage(identity,request.districtId(),result));
        });
    }

    private Mono<List<AlertResponse>> openOutageAlerts(TenantIdentity identity,OutageScenarioRequest request,
            String districtName,UUID commandId,List<TelemetryVehicle> vehicles) {
        Map<String,Object> detail=Map.of("scenario","outage","district_name",districtName,
                "command_id",commandId.toString(),"duration_seconds",request.effectiveDurationSeconds(),
                "affected_vehicles",vehicles.size());
        String detailJson=domain.writeJson(detail);
        return domain.openOrUpdateDistrictAlert(identity.tenantId(),request.districtId(),"district_outage","critical",detailJson)
                .flatMap(districtAlert->Flux.fromIterable(vehicles)
                        .concatMap(vehicle->domain.openOrUpdateVehicleAlert(identity.tenantId(),vehicle.vehicleId(),"district_outage","critical",
                                domain.writeJson(Map.of("scenario","outage","district_id",request.districtId().toString(),
                                        "command_id",commandId.toString()))))
                        .collectList().map(vehicleAlerts->{List<AlertResponse> result=new ArrayList<>(vehicleAlerts.size()+1);
                            result.add(districtAlert);result.addAll(vehicleAlerts);return List.copyOf(result);}));
    }

    private Mono<OutageScenarioResponse> publishOutage(TenantIdentity identity,UUID district,PreparedOutage prepared) {
        Mono<Void> alertEvents=Flux.fromIterable(prepared.alerts()).concatMap(alert->
                redis.convertAndSend(TelemetryLiveRepository.alertChannel(identity.tenantId()),domain.writeJson(alert))
                        .onErrorResume(error->{log.error("Outage alert saved but SSE publish failed; alertId={}",alert.id(),error);return Mono.just(0L);})).then();
        Mono<Void> liveEvents=Flux.fromIterable(prepared.vehicles()).concatMap(vehicle->live.markFault(identity,vehicle)
                .onErrorResume(error->{log.error("Outage vehicle status saved but live map update failed; vehicleId={}",vehicle.vehicleId(),error);return Mono.empty();})).then();
        AlertResponse districtAlert=prepared.alerts().getFirst();
        return Mono.when(alertEvents,liveEvents).then(Mono.fromSupplier(()->{
            meters.counter("scenario.outage.triggered").increment();
            meters.summary("scenario.outage.affected_vehicles").record(prepared.vehicles().size());
            return new OutageScenarioResponse(prepared.commandId(),district,prepared.vehicles().size(),districtAlert);
        }));
    }

    public Mono<List<ScenarioCommandResponse>> pollCommands(int requestedLimit) {
        int limit=requestedLimit;
        if(limit<1||limit>50) return Mono.error(new ApiException(HttpStatus.BAD_REQUEST,"INVALID_LIMIT","Command limit must be between 1 and 50."));
        return DomainSupport.identity().flatMap(identity->transactions.inTenantTransaction(
                ()->scenarios.expirePending(identity.tenantId()).then(scenarios.claimPending(identity.tenantId(),limit).collectList())));
    }

    public Mono<Void> acknowledge(UUID commandId) {
        return DomainSupport.identity().flatMap(identity->transactions.inTenantTransaction(()->
                scenarios.acknowledge(identity.tenantId(),commandId).flatMap(acknowledged->acknowledged?Mono.empty():
                        Mono.error(new ApiException(HttpStatus.NOT_FOUND,"COMMAND_NOT_FOUND","Command was not found, expired, or was already acknowledged.")))));
    }

    private record PreparedOutage(UUID commandId,List<TelemetryVehicle> vehicles,List<AlertResponse> alerts) {}
}
