package com.fleetcontrol.backend.repository;

import com.fleetcontrol.backend.dto.response.*;
import com.fleetcontrol.backend.model.FleetMinuteAggregate;
import com.fleetcontrol.backend.model.VehicleMinuteAggregate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.OffsetDateTime;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** SQL access for Phase 4 domain resources. Call only inside TenantTransaction. */
@Repository
public class DomainRepository {
    private final DatabaseClient db;
    private final ObjectMapper json;
    public DomainRepository(DatabaseClient db, ObjectMapper json) { this.db = db; this.json=json; }

    public Mono<MeResponse> me(UUID userId) {
        return db.sql("SELECT u.id,u.email::text,u.display_name,u.role,u.tenant_id,t.name tenant_name FROM public.users u JOIN public.tenants t ON t.id=u.tenant_id WHERE u.id=:id AND u.disabled_at IS NULL")
                .bind("id",userId).map((r,m)->new MeResponse(r.get("id",UUID.class),r.get("email",String.class),r.get("display_name",String.class),r.get("role",String.class),r.get("tenant_id",UUID.class),r.get("tenant_name",String.class))).one();
    }
    /** Aggregate view access is tenant-filtered by the security-barrier SQL views. */
    public Flux<VehicleMinuteAggregate> vehicleHistory(UUID vehicleId,OffsetDateTime from,OffsetDateTime to,int limit) {
        return db.sql("SELECT vehicle_id,bucket,battery_avg,battery_min,speed_avg,speed_max,sample_count FROM app.telemetry_1m WHERE vehicle_id=:vehicle AND bucket>=:from AND bucket<=:to ORDER BY bucket LIMIT :limit")
                .bind("vehicle",vehicleId).bind("from",from).bind("to",to).bind("limit",limit)
                .map((r,m)->new VehicleMinuteAggregate(r.get("vehicle_id",UUID.class),r.get("bucket",OffsetDateTime.class),r.get("battery_avg",Double.class),r.get("battery_min",Integer.class),r.get("speed_avg",Double.class),r.get("speed_max",Double.class),r.get("sample_count",Long.class))).all();
    }
    public Flux<FleetMinuteAggregate> fleetHistory(OffsetDateTime from,OffsetDateTime to,int limit) {
        return db.sql("SELECT bucket,battery_avg,battery_min,sample_count FROM app.telemetry_fleet_1m WHERE bucket>=:from AND bucket<=:to ORDER BY bucket LIMIT :limit")
                .bind("from",from).bind("to",to).bind("limit",limit)
                .map((r,m)->new FleetMinuteAggregate(r.get("bucket",OffsetDateTime.class),r.get("battery_avg",Double.class),r.get("battery_min",Integer.class),r.get("sample_count",Long.class))).all();
    }
    public Flux<VehicleResponse> vehicles(String status, UUID district, UUID cursor, int count) {
        String sql="SELECT id,external_ref,kind,home_district_id,status,created_at,retired_at FROM public.vehicles WHERE (:status IS NULL OR status=:status) AND (:district IS NULL OR home_district_id=:district) AND (:cursor IS NULL OR id>:cursor) ORDER BY id LIMIT :count";
        var q=db.sql(sql).bind("count",count);
        q=status==null?q.bindNull("status",String.class):q.bind("status",status);
        q=district==null?q.bindNull("district",UUID.class):q.bind("district",district);
        q=cursor==null?q.bindNull("cursor",UUID.class):q.bind("cursor",cursor);
        return q.map((r,m)->vehicle(r)).all();
    }
    public Mono<VehicleResponse> vehicle(UUID id) {
        return db.sql("SELECT id,external_ref,kind,home_district_id,status,created_at,retired_at FROM public.vehicles WHERE id=:id")
                .bind("id",id).map((r,m)->vehicle(r)).one();
    }
    private static VehicleResponse vehicle(io.r2dbc.spi.Row r) { return new VehicleResponse(r.get("id",UUID.class),r.get("external_ref",String.class),r.get("kind",String.class),r.get("home_district_id",UUID.class),r.get("status",String.class),r.get("created_at",OffsetDateTime.class),r.get("retired_at",OffsetDateTime.class),null); }

    public Flux<DistrictResponse> districts(UUID cursor,int count) {
        String sql="SELECT id,name,ST_AsGeoJSON(boundary::geometry) boundary_json,created_at FROM public.districts WHERE (:cursor IS NULL OR id>:cursor) ORDER BY id LIMIT :count";
        var q=db.sql(sql).bind("count",count); q=cursor==null?q.bindNull("cursor",UUID.class):q.bind("cursor",cursor);
        return q.map((r,m)->district(r)).all();
    }
    public Mono<DistrictResponse> createDistrict(UUID tenant,String name,String geoJson) {
        return db.sql("INSERT INTO public.districts(tenant_id,name,boundary) VALUES(:tenant,:name,ST_SetSRID(ST_GeomFromGeoJSON(:geo),4326)::geography) RETURNING id,name,ST_AsGeoJSON(boundary::geometry) boundary_json,created_at")
                .bind("tenant",tenant).bind("name",name).bind("geo",geoJson)
                .map((r,m)->district(r)).one();
    }
    private DistrictResponse district(io.r2dbc.spi.Row r) { return new DistrictResponse(r.get("id",UUID.class),r.get("name",String.class),readJson(r.get("boundary_json",String.class)),r.get("created_at",OffsetDateTime.class)); }

    public Flux<AlertResponse> alerts(String status,String severity,UUID cursor,int count) {
        String sql="SELECT id,vehicle_id,district_id,kind,severity,status,opened_at,acknowledged_at,resolved_at,detail::text detail FROM public.alerts WHERE (:status IS NULL OR status=:status) AND (:severity IS NULL OR severity=:severity) AND (:cursor IS NULL OR id>:cursor) ORDER BY id LIMIT :count";
        var q=db.sql(sql).bind("count",count); q=status==null?q.bindNull("status",String.class):q.bind("status",status); q=severity==null?q.bindNull("severity",String.class):q.bind("severity",severity); q=cursor==null?q.bindNull("cursor",UUID.class):q.bind("cursor",cursor);
        return q.map((r,m)->alert(r)).all();
    }
    public Mono<AlertResponse> acknowledge(UUID id) {
        return db.sql("UPDATE public.alerts SET status='acknowledged',acknowledged_at=COALESCE(acknowledged_at,now()) WHERE id=:id AND status='open' RETURNING id,vehicle_id,district_id,kind,severity,status,opened_at,acknowledged_at,resolved_at,detail::text detail")
                .bind("id",id).map((r,m)->alert(r)).one();
    }
    public Mono<AlertResponse> resolve(UUID id) {
        return db.sql("UPDATE public.alerts SET status='resolved',resolved_at=now() WHERE id=:id AND status IN ('open','acknowledged') RETURNING id,vehicle_id,district_id,kind,severity,status,opened_at,acknowledged_at,resolved_at,detail::text detail")
                .bind("id",id).map((r,m)->alert(r)).one();
    }
    private AlertResponse alert(io.r2dbc.spi.Row r) { return new AlertResponse(r.get("id",UUID.class),r.get("vehicle_id",UUID.class),r.get("district_id",UUID.class),r.get("kind",String.class),r.get("severity",String.class),r.get("status",String.class),r.get("opened_at",OffsetDateTime.class),r.get("acknowledged_at",OffsetDateTime.class),r.get("resolved_at",OffsetDateTime.class),readJson(r.get("detail",String.class))); }
    public Mono<Boolean> alertExists(UUID id) { return db.sql("SELECT EXISTS(SELECT 1 FROM public.alerts WHERE id=:id)").bind("id",id).map((r,m)->Boolean.TRUE.equals(r.get(0,Boolean.class))).one(); }
    public Mono<AlertResponse> openOrUpdateVehicleAlert(UUID tenant,UUID vehicle,String kind,String severity,String detailJson) {
        return db.sql("INSERT INTO public.alerts(tenant_id,vehicle_id,kind,severity,detail) VALUES(:tenant,:vehicle,:kind,:severity,CAST(:detail AS jsonb)) "+
                "ON CONFLICT(tenant_id,vehicle_id,kind) WHERE vehicle_id IS NOT NULL AND status<>'resolved' "+
                "DO UPDATE SET severity=EXCLUDED.severity,detail=EXCLUDED.detail "+
                "RETURNING id,vehicle_id,district_id,kind,severity,status,opened_at,acknowledged_at,resolved_at,detail::text detail")
                .bind("tenant",tenant).bind("vehicle",vehicle).bind("kind",kind).bind("severity",severity).bind("detail",detailJson)
                .map((r,m)->alert(r)).one();
    }
    public Mono<AlertResponse> resolveVehicleAlert(UUID vehicle,String kind) {
        return db.sql("UPDATE public.alerts SET status='resolved',resolved_at=now() WHERE vehicle_id=:vehicle AND kind=:kind AND status<>'resolved' "+
                "RETURNING id,vehicle_id,district_id,kind,severity,status,opened_at,acknowledged_at,resolved_at,detail::text detail")
                .bind("vehicle",vehicle).bind("kind",kind).map((r,m)->alert(r)).one();
    }

    public Flux<DashboardResponse> dashboards(UUID user,UUID cursor,int count) {
        String sql="SELECT id,owner_id,name,is_shared,created_at,updated_at FROM public.dashboards WHERE (owner_id=:user OR is_shared=true) AND (:cursor IS NULL OR id>:cursor) ORDER BY id LIMIT :count";
        var q=db.sql(sql).bind("user",user).bind("count",count); q=cursor==null?q.bindNull("cursor",UUID.class):q.bind("cursor",cursor);
        return q.map((r,m)->dashboard(r,List.of())).all();
    }
    public Mono<DashboardResponse> dashboard(UUID id,UUID user) {
        return db.sql("SELECT id,owner_id,name,is_shared,created_at,updated_at FROM public.dashboards WHERE id=:id AND (owner_id=:user OR is_shared=true)").bind("id",id).bind("user",user).map((r,m)->dashboard(r,List.of())).one()
                .flatMap(d->widgets(id).collectList().map(ws->new DashboardResponse(d.id(),d.ownerId(),d.name(),d.shared(),d.createdAt(),d.updatedAt(),ws)));
    }
    private static DashboardResponse dashboard(io.r2dbc.spi.Row r,List<DashboardResponse.Widget> widgets) { return new DashboardResponse(r.get("id",UUID.class),r.get("owner_id",UUID.class),r.get("name",String.class),r.get("is_shared",Boolean.class),r.get("created_at",OffsetDateTime.class),r.get("updated_at",OffsetDateTime.class),widgets); }
    public Flux<DashboardResponse.Widget> widgets(UUID dashboardId) { return db.sql("SELECT id,type,config::text config,grid_x,grid_y,grid_w,grid_h FROM public.dashboard_widgets WHERE dashboard_id=:id ORDER BY grid_y,grid_x,id").bind("id",dashboardId).map((r,m)->new DashboardResponse.Widget(r.get("id",UUID.class),r.get("type",String.class),readJson(r.get("config",String.class)),r.get("grid_x",Integer.class),r.get("grid_y",Integer.class),r.get("grid_w",Integer.class),r.get("grid_h",Integer.class))).all(); }
    public Mono<DashboardResponse> createDashboard(UUID tenant,UUID owner,String name,boolean shared) {
        return db.sql("INSERT INTO public.dashboards(tenant_id,owner_id,name,is_shared) VALUES(:tenant,:owner,:name,:shared) RETURNING id,owner_id,name,is_shared,created_at,updated_at")
                .bind("tenant",tenant).bind("owner",owner).bind("name",name).bind("shared",shared).map((r,m)->dashboard(r,List.of())).one();
    }
    public Mono<Boolean> updateDashboard(UUID id,UUID owner,String name,boolean shared) {
        return db.sql("UPDATE public.dashboards SET name=:name,is_shared=:shared,updated_at=now() WHERE id=:id AND owner_id=:owner RETURNING id")
                .bind("id",id).bind("owner",owner).bind("name",name).bind("shared",shared).map((r,m)->true).one().defaultIfEmpty(false);
    }
    public Mono<Boolean> deleteDashboard(UUID id,UUID owner) {
        return db.sql("DELETE FROM public.dashboards WHERE id=:id AND owner_id=:owner RETURNING id").bind("id",id).bind("owner",owner).map((r,m)->true).one().defaultIfEmpty(false);
    }
    public Mono<Void> deleteWidgets(UUID id) { return db.sql("DELETE FROM public.dashboard_widgets WHERE dashboard_id=:id").bind("id",id).fetch().rowsUpdated().then(); }
    public Mono<Void> addWidget(UUID tenant,UUID dashboard,String type,String config,int x,int y,int w,int h) {
        return db.sql("INSERT INTO public.dashboard_widgets(tenant_id,dashboard_id,type,config,grid_x,grid_y,grid_w,grid_h) VALUES(:tenant,:dashboard,:type,CAST(:config AS jsonb),:x,:y,:w,:h)")
                .bind("tenant",tenant).bind("dashboard",dashboard).bind("type",type).bind("config",config).bind("x",x).bind("y",y).bind("w",w).bind("h",h).fetch().rowsUpdated().then();
    }
    public Mono<Boolean> dashboardOwned(UUID id,UUID owner) { return db.sql("SELECT EXISTS(SELECT 1 FROM public.dashboards WHERE id=:id AND owner_id=:owner)").bind("id",id).bind("owner",owner).map((r,m)->Boolean.TRUE.equals(r.get(0,Boolean.class))).one(); }
    public String writeJson(Object value) { return json.writeValueAsString(value); }
    @SuppressWarnings("unchecked") private <T> T readJson(String value) { return (T)json.readValue(value,Object.class); }
}
