package com.fleetcontrol.backend.repository;

import com.fleetcontrol.backend.dto.request.TelemetryBatchRequest;
import com.fleetcontrol.backend.model.TelemetryVehicle;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Tenant-owned telemetry SQL. Every call is made inside TenantTransaction. */
@Repository
public class TelemetryRepository {
    private final DatabaseClient db;
    public TelemetryRepository(DatabaseClient db) { this.db=db; }

    public Mono<Set<UUID>> activeVehicleIds(UUID tenantId) {
        return db.sql("SELECT id FROM public.vehicles WHERE tenant_id=:tenant AND retired_at IS NULL")
                .bind("tenant",tenantId).map((r,m)->r.get("id",UUID.class)).all().collect(Collectors.toUnmodifiableSet());
    }

    public Mono<TelemetryVehicle> latest(UUID tenantId,UUID vehicleId) {
        return db.sql("SELECT vehicle_id,ts,max(seq) OVER() AS seq,lat,lon,heading_deg,speed_kmh,battery_pct,status FROM public.telemetry WHERE tenant_id=:tenant AND vehicle_id=:vehicle ORDER BY ts DESC LIMIT 1")
                .bind("tenant",tenantId).bind("vehicle",vehicleId).map((r,m)->new TelemetryVehicle(
                        r.get("vehicle_id",UUID.class),r.get("ts",OffsetDateTime.class).toInstant(),r.get("seq",Long.class),
                        r.get("lat",Double.class),r.get("lon",Double.class),r.get("heading_deg",Float.class),
                        r.get("speed_kmh",Float.class),r.get("battery_pct",Short.class).intValue(),r.get("status",String.class))).one();
    }
    public Flux<TelemetryVehicle> latestForTenant(UUID tenantId) {
        return db.sql("SELECT DISTINCT ON(vehicle_id) vehicle_id,ts,max(seq) OVER(PARTITION BY vehicle_id) AS seq,lat,lon,heading_deg,speed_kmh,battery_pct,status FROM public.telemetry WHERE tenant_id=:tenant ORDER BY vehicle_id,ts DESC")
                .bind("tenant",tenantId).map((r,m)->new TelemetryVehicle(r.get("vehicle_id",UUID.class),r.get("ts",OffsetDateTime.class).toInstant(),r.get("seq",Long.class),
                        r.get("lat",Double.class),r.get("lon",Double.class),r.get("heading_deg",Float.class),r.get("speed_kmh",Float.class),
                        r.get("battery_pct",Short.class).intValue(),r.get("status",String.class))).all();
    }
    public Flux<TelemetryVehicle> latestForVehicles(UUID tenantId,List<UUID> vehicleIds) {
        if(vehicleIds.isEmpty()) return Flux.empty();
        StringJoiner values=new StringJoiner(","); for(int i=0;i<vehicleIds.size();i++) values.add("(:vehicle"+i+")");
        String sql="SELECT DISTINCT ON(t.vehicle_id) t.vehicle_id,t.ts,max(t.seq) OVER(PARTITION BY t.vehicle_id) AS seq,t.lat,t.lon,t.heading_deg,t.speed_kmh,t.battery_pct,t.status "+
                "FROM public.telemetry t JOIN (VALUES "+values+") AS requested(id) ON requested.id=t.vehicle_id "+
                "WHERE t.tenant_id=:tenant ORDER BY t.vehicle_id,t.ts DESC";
        var q=db.sql(sql).bind("tenant",tenantId); for(int i=0;i<vehicleIds.size();i++) q=q.bind("vehicle"+i,vehicleIds.get(i));
        return q.map((r,m)->new TelemetryVehicle(r.get("vehicle_id",UUID.class),r.get("ts",OffsetDateTime.class).toInstant(),r.get("seq",Long.class),
                r.get("lat",Double.class),r.get("lon",Double.class),r.get("heading_deg",Float.class),r.get("speed_kmh",Float.class),
                r.get("battery_pct",Short.class).intValue(),r.get("status",String.class))).all();
    }

    /** One multi-row insert per group, with idempotent (vehicle_id, ts) conflict handling. */
    public Mono<Long> persistBatch(UUID tenantId,List<TelemetryBatchRequest.TelemetryEvent> events,List<Long> sequences) {
        if(events.isEmpty()) return Mono.just(0L);
        StringJoiner rows=new StringJoiner(",");
        for(int i=0;i<events.size();i++) rows.add("(:tenant,:vehicle"+i+",:ts"+i+",:seq"+i+",:lat"+i+",:lon"+i+",:heading"+i+",:speed"+i+",:battery"+i+",:status"+i+")");
        String sql="WITH input(tenant_id,vehicle_id,ts,seq,lat,lon,heading_deg,speed_kmh,battery_pct,status) AS (VALUES "+rows+") "+
                "INSERT INTO public.telemetry(tenant_id,vehicle_id,ts,seq,lat,lon,heading_deg,speed_kmh,battery_pct,status) "+
                "SELECT tenant_id,vehicle_id,ts,seq,lat,lon,heading_deg,speed_kmh,battery_pct,status FROM input ON CONFLICT(vehicle_id,ts) DO NOTHING";
        var q=db.sql(sql);
        for(int i=0;i<events.size();i++) {
            var e=events.get(i);
            q=q.bind("tenant",tenantId).bind("vehicle"+i,e.vehicleId()).bind("ts"+i,OffsetDateTime.ofInstant(e.ts(),java.time.ZoneOffset.UTC))
                    .bind("seq"+i,sequences.get(i)).bind("lat"+i,e.lat()).bind("lon"+i,e.lon());
            q=e.headingDeg()==null?q.bindNull("heading"+i,Float.class):q.bind("heading"+i,e.headingDeg());
            q=e.speedKmh()==null?q.bindNull("speed"+i,Float.class):q.bind("speed"+i,e.speedKmh());
            q=q.bind("battery"+i,(short)e.batteryPct()).bind("status"+i,e.status());
        }
        return q.fetch().rowsUpdated().map(Long::valueOf);
    }

    public Mono<Void> refreshVehicleStatuses(UUID tenantId,List<UUID> vehicleIds) {
        if(vehicleIds.isEmpty()) return Mono.empty();
        StringJoiner values=new StringJoiner(",");
        for(int i=0;i<vehicleIds.size();i++) values.add("(:vehicle"+i+")");
        String sql="WITH requested(id) AS (VALUES "+values+"), latest AS (SELECT DISTINCT ON(t.vehicle_id) t.vehicle_id,t.status FROM public.telemetry t JOIN requested r ON r.id=t.vehicle_id ORDER BY t.vehicle_id,t.ts DESC) "+
                "UPDATE public.vehicles v SET status=l.status FROM latest l WHERE v.id=l.vehicle_id AND v.tenant_id=:tenant";
        var q=db.sql(sql).bind("tenant",tenantId);
        for(int i=0;i<vehicleIds.size();i++) q=q.bind("vehicle"+i,vehicleIds.get(i));
        return q.fetch().rowsUpdated().then();
    }
    public Mono<Void> markVehiclesOffline(UUID tenantId,List<UUID> vehicleIds) {
        if(vehicleIds.isEmpty()) return Mono.empty();
        StringJoiner values=new StringJoiner(","); for(int i=0;i<vehicleIds.size();i++) values.add("(:vehicle"+i+")");
        var q=db.sql("UPDATE public.vehicles SET status='offline' WHERE tenant_id=:tenant AND id IN (SELECT id FROM (VALUES "+values+") AS requested(id))")
                .bind("tenant",tenantId);
        for(int i=0;i<vehicleIds.size();i++) q=q.bind("vehicle"+i,vehicleIds.get(i));
        return q.fetch().rowsUpdated().then();
    }
}
