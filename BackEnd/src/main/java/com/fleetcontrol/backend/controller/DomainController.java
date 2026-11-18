package com.fleetcontrol.backend.controller;

import com.fleetcontrol.backend.dto.request.CreateDistrictRequest;
import com.fleetcontrol.backend.dto.request.DashboardRequest;
import com.fleetcontrol.backend.dto.response.*;
import com.fleetcontrol.backend.service.DomainService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.UUID;

@RestController
@RequestMapping(path="/api", produces=MediaType.APPLICATION_JSON_VALUE)
public class DomainController {
    private final DomainService service;
    public DomainController(DomainService service) { this.service=service; }

    @GetMapping("/me") public Mono<MeResponse> me() { return service.me(); }

    @GetMapping("/vehicles") public Mono<PageResponse<VehicleResponse>> vehicles(
            @RequestParam(required=false) String status,@RequestParam(required=false) String district,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit) {
        return service.vehicles(status,district,cursor,limit);
    }
    @GetMapping("/vehicles/{id}") public Mono<VehicleResponse> vehicle(@PathVariable UUID id) { return service.vehicle(id); }

    @GetMapping("/districts") public Mono<PageResponse<DistrictResponse>> districts(
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit) {
        return service.districts(cursor,limit);
    }
    @PostMapping(path="/districts",consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<DistrictResponse> createDistrict(@Valid @RequestBody CreateDistrictRequest request) { return service.createDistrict(request); }

    @GetMapping("/alerts") public Mono<PageResponse<AlertResponse>> alerts(
            @RequestParam(required=false) String status,@RequestParam(required=false) String severity,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit) {
        return service.alerts(status,severity,cursor,limit);
    }
    @PostMapping("/alerts/{id}/acknowledge") public Mono<AlertResponse> acknowledge(@PathVariable UUID id) { return service.acknowledge(id); }
    @PostMapping("/alerts/{id}/resolve") public Mono<AlertResponse> resolve(@PathVariable UUID id) { return service.resolve(id); }

    @GetMapping("/dashboards") public Mono<PageResponse<DashboardResponse>> dashboards(
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit) { return service.dashboards(cursor,limit); }
    @GetMapping("/dashboards/{id}") public Mono<DashboardResponse> dashboard(@PathVariable UUID id) { return service.dashboard(id); }
    @PostMapping(path="/dashboards",consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<DashboardResponse> createDashboard(@Valid @RequestBody DashboardRequest request) { return service.createDashboard(request); }
    @PutMapping(path="/dashboards/{id}",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Mono<DashboardResponse> replaceDashboard(@PathVariable UUID id,@Valid @RequestBody DashboardRequest request) { return service.replaceDashboard(id,request); }
    @DeleteMapping("/dashboards/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteDashboard(@PathVariable UUID id) { return service.deleteDashboard(id); }
}
