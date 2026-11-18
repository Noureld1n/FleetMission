package com.fleetcontrol.backend.repository;

import com.fleetcontrol.backend.dto.response.ScenarioCommandResponse;
import com.fleetcontrol.backend.model.TelemetryVehicle;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** Tenant-scoped outage scenario and simulator command queries. Called inside TenantTransaction. */
@Repository
public class ScenarioRepository {
    private final DatabaseClient db;
    private final ObjectMapper json;
    public ScenarioRepository(DatabaseClient db,ObjectMapper json) { this.db=db;this.json=json; }

    public Mono<String> districtName(UUID tenant, UUID district) {
        return db.sql("SELECT name FROM public.districts WHERE tenant_id=:tenant AND id=:district")
                .bind("tenant",tenant).bind("district",district).map((row,meta)->row.get("name",String.class)).one();
    }

    /** Uses each vehicle's newest recent telemetry position and the tenant-owned district geofence. */
    public Flux<TelemetryVehicle> vehiclesInDistrict(UUID tenant, UUID district, OffsetDateTime since) {
        String sql="WITH latest AS (SELECT DISTINCT ON(t.vehicle_id) t.vehicle_id,t.ts,t.seq,t.lat,t.lon,t.heading_deg,t.speed_kmh,t.battery_pct,t.status "+
                "FROM public.telemetry t JOIN public.vehicles v ON v.tenant_id=t.tenant_id AND v.id=t.vehicle_id "+
                "WHERE t.tenant_id=:tenant AND v.retired_at IS NULL AND t.ts>=:since ORDER BY t.vehicle_id,t.ts DESC) "+
                "SELECT l.vehicle_id,l.ts,l.seq,l.lat,l.lon,l.heading_deg,l.speed_kmh,l.battery_pct,l.status "+
                "FROM latest l JOIN public.districts d ON d.tenant_id=:tenant AND d.id=:district "+
                "WHERE ST_Covers(d.boundary::geometry,ST_SetSRID(ST_MakePoint(l.lon,l.lat),4326))";
        return db.sql(sql).bind("tenant",tenant).bind("district",district).bind("since",since)
                .map((row,meta)->new TelemetryVehicle(row.get("vehicle_id",UUID.class),row.get("ts",OffsetDateTime.class).toInstant(),
                        row.get("seq",Long.class),row.get("lat",Double.class),row.get("lon",Double.class),
                        row.get("heading_deg",Float.class),row.get("speed_kmh",Float.class),row.get("battery_pct",Short.class).intValue(),
                        row.get("status",String.class))).all();
    }

    public Mono<UUID> createOutageCommand(UUID tenant,UUID district,Map<String,Object> payload) {
        return db.sql("INSERT INTO public.scenario_commands(tenant_id,district_id,command_type,payload,expires_at) "+
                        "VALUES(:tenant,:district,'outage',CAST(:payload AS jsonb),now()+interval '10 minutes') RETURNING id")
                .bind("tenant",tenant).bind("district",district).bind("payload",json.writeValueAsString(payload)).map((row,meta)->row.get("id",UUID.class)).one();
    }

    public Flux<ScenarioCommandResponse> claimPending(UUID tenant,int limit) {
        String sql="UPDATE public.scenario_commands c SET lease_until=now()+interval '30 seconds',attempt_count=c.attempt_count+1 "+
                "WHERE c.id IN (SELECT id FROM public.scenario_commands WHERE tenant_id=:tenant AND state='pending' "+
                "AND expires_at>now() AND (lease_until IS NULL OR lease_until<=now()) ORDER BY created_at,id "+
                "FOR UPDATE SKIP LOCKED LIMIT :limit) "+
                "RETURNING c.id,c.command_type,c.payload::text payload,c.attempt_count,c.expires_at,c.lease_until";
        return db.sql(sql).bind("tenant",tenant).bind("limit",limit).map((row,meta)->new ScenarioCommandResponse(
                row.get("id",UUID.class),row.get("command_type",String.class),readJson(row.get("payload",String.class)),
                row.get("attempt_count",Integer.class),row.get("expires_at",OffsetDateTime.class),row.get("lease_until",OffsetDateTime.class))).all();
    }
    public Mono<Void> expirePending(UUID tenant) {
        return db.sql("UPDATE public.scenario_commands SET state='expired',lease_until=NULL "+
                        "WHERE tenant_id=:tenant AND state='pending' AND expires_at<=now()")
                .bind("tenant",tenant).fetch().rowsUpdated().then();
    }

    public Mono<Boolean> acknowledge(UUID tenant,UUID commandId) {
        return db.sql("UPDATE public.scenario_commands SET state='acknowledged',acknowledged_at=now(),lease_until=NULL "+
                        "WHERE tenant_id=:tenant AND id=:id AND state='pending' AND expires_at>now() RETURNING id")
                .bind("tenant",tenant).bind("id",commandId).map((row,meta)->true).one().defaultIfEmpty(false)
                .flatMap(updated->updated?Mono.just(true):db.sql("SELECT state='acknowledged' acknowledged FROM public.scenario_commands WHERE tenant_id=:tenant AND id=:id")
                        .bind("tenant",tenant).bind("id",commandId).map((row,meta)->Boolean.TRUE.equals(row.get("acknowledged",Boolean.class))).one().defaultIfEmpty(false));
    }

    @SuppressWarnings("unchecked") private Map<String,Object> readJson(String value) {
        return (Map<String,Object>)json.readValue(value,Map.class);
    }
}
