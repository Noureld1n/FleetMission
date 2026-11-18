package com.fleetcontrol.backend.service;

import com.fleetcontrol.backend.dto.request.CreateDistrictRequest;
import com.fleetcontrol.backend.dto.request.DashboardRequest;
import com.fleetcontrol.backend.dto.response.*;
import com.fleetcontrol.backend.exception.ApiException;
import com.fleetcontrol.backend.mapper.DomainMapper;
import com.fleetcontrol.backend.model.*;
import com.fleetcontrol.backend.repository.DomainRepository;
import com.fleetcontrol.backend.security.TenantIdentity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;

@Service
public class DomainService {
    private static final Logger log=LoggerFactory.getLogger(DomainService.class);
    private final DomainRepository repository;
    private final TenantTransaction transactions;
    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;
    public DomainService(DomainRepository repository,TenantTransaction transactions,ReactiveStringRedisTemplate redis,Clock clock) {
        this.repository=repository; this.transactions=transactions; this.redis=redis; this.clock=clock;
    }

    public Mono<MeResponse> me() { return DomainSupport.identity().flatMap(id->inTransaction(()->repository.me(id.subjectId()).switchIfEmpty(notFound("USER_NOT_FOUND","Current user was not found.")))); }
    public Mono<PageResponse<VehicleResponse>> vehicles(String status,String district,String cursor,int limit) {
        String s=DomainSupport.enumValue(VehicleStatus.class,status,"VEHICLE_STATUS"); UUID d=uuid(district,"district"); UUID c=DomainSupport.cursor(cursor); int n=DomainSupport.limit(limit);
        return inTransaction(()->repository.vehicles(s,d,c,n+1).collectList().map(rows->page(rows,n,VehicleResponse::id)));
    }
    public Mono<VehicleResponse> vehicle(UUID id) {
        return DomainSupport.identity().flatMap(identity->inTransaction(()->repository.vehicle(id)
                .switchIfEmpty(notFound("VEHICLE_NOT_FOUND","Vehicle was not found.")))
                .flatMap(vehicle->redis.<String,String>opsForHash().entries(vehicleKey(vehicle.id(),identity.tenantId())).collectMap(Map.Entry::getKey,Map.Entry::getValue)
                        .map(state->{state.remove("ts_epoch_ms");return new VehicleResponse(vehicle.id(),vehicle.externalRef(),vehicle.kind(),vehicle.homeDistrictId(),vehicle.status(),vehicle.createdAt(),vehicle.retiredAt(),state.isEmpty()?null:Map.copyOf(state));})
                        .defaultIfEmpty(vehicle)));
    }
    public Mono<VehicleHistoryResponse> vehicleHistory(UUID vehicleId,Instant from,Instant to) {
        TimeRange range=timeRange(from,to);
        return inTransaction(()->repository.vehicle(vehicleId)
                .switchIfEmpty(notFound("VEHICLE_NOT_FOUND","Vehicle was not found."))
                .then(repository.vehicleHistory(vehicleId,range.from().atOffset(ZoneOffset.UTC),range.to().atOffset(ZoneOffset.UTC),range.limit()).collectList())
                .map(points->new VehicleHistoryResponse(vehicleId,range.from(),range.to(),points.stream().map(DomainMapper::toVehicleHistoryPoint).toList())));
    }
    public Mono<FleetMetricsResponse> fleetMetrics(Instant from,Instant to) {
        TimeRange range=timeRange(from,to);
        return inTransaction(()->repository.fleetHistory(range.from().atOffset(ZoneOffset.UTC),range.to().atOffset(ZoneOffset.UTC),range.limit())
                .map(DomainMapper::toFleetMetricsPoint).collectList().map(points->new FleetMetricsResponse(range.from(),range.to(),points)));
    }
    private TimeRange timeRange(Instant requestedFrom,Instant requestedTo) {
        Instant now=clock.instant();
        Instant end=requestedTo==null?now:requestedTo;
        Instant start=requestedFrom==null?end.minus(Duration.ofHours(24)):requestedFrom;
        if(start.isAfter(end)) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_TIME_RANGE","The from timestamp must be at or before to.");
        if(end.isAfter(now)) throw new ApiException(HttpStatus.BAD_REQUEST,"FUTURE_TIME_RANGE","The to timestamp cannot be in the future.");
        if(Duration.between(start,end).compareTo(Duration.ofDays(7))>0)
            throw new ApiException(HttpStatus.BAD_REQUEST,"TIME_RANGE_TOO_LARGE","History and metrics queries are limited to seven days.");
        int maxPoints=(int)(Duration.between(start,end).toMinutes()+2);
        return new TimeRange(start,end,Math.max(1,maxPoints));
    }
    private record TimeRange(Instant from,Instant to,int limit) {}
    private static String vehicleKey(UUID id,UUID tenant) { return "t:"+tenant+":v:"+id; }
    public Mono<PageResponse<DistrictResponse>> districts(String cursor,int limit) {
        UUID c=DomainSupport.cursor(cursor); int n=DomainSupport.limit(limit);
        return inTransaction(()->repository.districts(c,n+1).collectList().map(rows->page(rows,n,DistrictResponse::id)));
    }
    public Mono<DistrictResponse> createDistrict(CreateDistrictRequest request) {
        validatePolygon(request.boundary()); String geo=DomainMapper.toGeoJson(request.boundary());
        return DomainSupport.identity().flatMap(id->inTransaction(()->repository.createDistrict(id.tenantId(),request.name().trim(),geo)
                .onErrorMap(DataIntegrityViolationException.class,e->new ApiException(HttpStatus.CONFLICT,"DISTRICT_CONFLICT","District name or boundary conflicts with an existing or invalid district."))));
    }
    public Mono<PageResponse<AlertResponse>> alerts(String status,String severity,String cursor,int limit) {
        String s=DomainSupport.enumValue(AlertStatus.class,status,"ALERT_STATUS"); String v=DomainSupport.enumValue(AlertSeverity.class,severity,"ALERT_SEVERITY"); UUID c=DomainSupport.cursor(cursor); int n=DomainSupport.limit(limit);
        return inTransaction(()->repository.alerts(s,v,c,n+1).collectList().map(rows->page(rows,n,AlertResponse::id)));
    }
    public Mono<AlertResponse> acknowledge(UUID id) { return transition(id,repository::acknowledge,"ALERT_NOT_FOUND","Alert was not found."); }
    public Mono<AlertResponse> resolve(UUID id) { return transition(id,repository::resolve,"ALERT_NOT_FOUND","Alert was not found."); }
    private Mono<AlertResponse> transition(UUID id,Function<UUID,Mono<AlertResponse>> action,String code,String detail) {
        return DomainSupport.identity().flatMap(identity->inTransaction(()->action.apply(id).switchIfEmpty(repository.alertExists(id).flatMap(exists->exists
                ? Mono.error(new ApiException(HttpStatus.CONFLICT,"INVALID_ALERT_TRANSITION","Alert is already in a state that cannot make this transition."))
                : Mono.error(new ApiException(HttpStatus.NOT_FOUND,code,detail)))))
                .flatMap(alert->redis.convertAndSend(com.fleetcontrol.backend.repository.TelemetryLiveRepository.alertChannel(identity.tenantId()),repository.writeJson(alert))
                        .thenReturn(alert)
                        .onErrorResume(error->{log.error("Alert transition committed but Redis notification failed; alertId={}",alert.id(),error);return Mono.just(alert);}))) ;
    }
    public Mono<PageResponse<DashboardResponse>> dashboards(String cursor,int limit) {
        UUID c=DomainSupport.cursor(cursor); int n=DomainSupport.limit(limit);
        return DomainSupport.identity().flatMap(id->inTransaction(()->repository.dashboards(id.subjectId(),c,n+1).collectList().map(rows->page(rows,n,DashboardResponse::id))));
    }
    public Mono<DashboardResponse> dashboard(UUID id) {
        return DomainSupport.identity().flatMap(user->inTransaction(()->repository.dashboard(id,user.subjectId())
                .switchIfEmpty(notFound("DASHBOARD_NOT_FOUND","Dashboard was not found."))));
    }
    public Mono<DashboardResponse> createDashboard(DashboardRequest request) {
        validateWidgets(request.widgets());
        return DomainSupport.identity().flatMap(id->inTransaction(()->repository.createDashboard(id.tenantId(),id.subjectId(),request.name().trim(),request.shared())
                .flatMap(d->insertWidgets(d.id(),id.tenantId(),request.widgets()).then(repository.dashboard(d.id(),id.subjectId())))
                .onErrorMap(DataIntegrityViolationException.class,e->new ApiException(HttpStatus.CONFLICT,"DASHBOARD_CONFLICT","A dashboard with that name already exists."))));
    }
    public Mono<DashboardResponse> replaceDashboard(UUID dashboardId,DashboardRequest request) {
        validateWidgets(request.widgets());
        return DomainSupport.identity().flatMap(id->inTransaction(()->repository.dashboardOwned(dashboardId,id.subjectId()).flatMap(owned->{
            if(!owned) return Mono.error(new ApiException(HttpStatus.NOT_FOUND,"DASHBOARD_NOT_FOUND","Dashboard was not found."));
            return repository.updateDashboard(dashboardId,id.subjectId(),request.name().trim(),request.shared())
                    .then(repository.deleteWidgets(dashboardId)).then(insertWidgets(dashboardId,id.tenantId(),request.widgets()))
                    .then(repository.dashboard(dashboardId,id.subjectId()));
        }).onErrorMap(DataIntegrityViolationException.class,e->new ApiException(HttpStatus.CONFLICT,"DASHBOARD_CONFLICT","A dashboard with that name already exists."))));
    }
    public Mono<Void> deleteDashboard(UUID dashboardId) {
        return DomainSupport.identity().flatMap(id->inTransaction(()->repository.deleteDashboard(dashboardId,id.subjectId()).flatMap(deleted->deleted?Mono.empty():notFound("DASHBOARD_NOT_FOUND","Dashboard was not found."))));
    }
    private Mono<Void> insertWidgets(UUID id,UUID tenant,List<DashboardRequest.Widget> widgets) {
        return reactor.core.publisher.Flux.fromIterable(widgets).concatMap(w->repository.addWidget(tenant,id,
                DomainMapper.widgetType(w),repository.writeJson(w.config()),w.gridX(),w.gridY(),w.gridW(),w.gridH())).then();
    }
    private void validateWidgets(List<DashboardRequest.Widget> widgets) { widgets.forEach(w->DomainSupport.enumValue(WidgetType.class,w.type(),"WIDGET_TYPE")); }
    private static void validatePolygon(CreateDistrictRequest.Polygon p) {
        if(!"Polygon".equalsIgnoreCase(p.type())) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_BOUNDARY","Boundary GeoJSON type must be Polygon.");
        for(var ring:p.coordinates()) {
            if(ring.size()<4) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_BOUNDARY","Each polygon ring needs at least four positions.");
            var first=ring.get(0); var last=ring.get(ring.size()-1);
            if(!first.equals(last)) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_BOUNDARY","Polygon rings must be closed.");
            for(var point:ring) if(!Double.isFinite(point.longitude()) || !Double.isFinite(point.latitude()) || point.longitude()< -180 || point.longitude()>180 || point.latitude()< -90 || point.latitude()>90)
                throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_BOUNDARY","Coordinates must be longitude/latitude within GeoJSON bounds.");
        }
    }
    private <T> Mono<T> inTransaction(java.util.function.Supplier<Mono<T>> work) { return transactions.inTenantTransaction(work); }
    private static UUID uuid(String raw,String field) { if(raw==null||raw.isBlank()) return null; try{return UUID.fromString(raw);}catch(IllegalArgumentException ex){throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_"+field.toUpperCase()+"_ID",field+" must be a UUID.");} }
    private static <T> PageResponse<T> page(List<T> rows,int limit,Function<T,UUID> id) { boolean more=rows.size()>limit; List<T> items=List.copyOf(rows.subList(0,Math.min(limit,rows.size()))); return new PageResponse<>(items,more?id.apply(items.get(items.size()-1)).toString():null); }
    private static <T> Mono<T> notFound(String code,String detail) { return Mono.error(new ApiException(HttpStatus.NOT_FOUND,code,detail)); }
}
