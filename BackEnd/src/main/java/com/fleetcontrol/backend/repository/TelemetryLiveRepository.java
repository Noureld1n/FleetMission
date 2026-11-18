package com.fleetcontrol.backend.repository;

import com.fleetcontrol.backend.config.AppProperties;
import com.fleetcontrol.backend.dto.request.TelemetryBatchRequest;
import com.fleetcontrol.backend.model.TelemetryVehicle;
import com.fleetcontrol.backend.security.TenantIdentity;
import com.fleetcontrol.backend.service.TenantTransaction;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import reactor.core.publisher.Flux;

/** Atomic Redis live-state update. All keys and channels carry the tenant ID. */
@Repository
public class TelemetryLiveRepository {
    private static final DefaultRedisScript<String> UPDATE_SCRIPT=new DefaultRedisScript<>("""
            local state=KEYS[1]
            local geo=KEYS[2]
            local counts=KEYS[3]
            local vehicle=ARGV[9]
            local zoom=tonumber(ARGV[10])
            local function tile(lat,lon)
              local n=2^zoom
              local x=math.floor((lon+180)/360*n)
              local safe=math.max(-85.05112878,math.min(85.05112878,lat))
              local rad=safe*math.pi/180
              local y=math.floor((1-math.log(math.tan(rad)+1/math.cos(rad))/math.pi)/2*n)
              return math.max(0,math.min(n-1,x)), math.max(0,math.min(n-1,y))
            end
            if redis.call('EXISTS',state)==0 and ARGV[11]~='' then
              redis.call('HSET',state,'seq',ARGV[11],'lat',ARGV[14],'lon',ARGV[15],
                'battery',ARGV[18],'status',ARGV[19],'ts',ARGV[13],'ts_epoch_ms',ARGV[12])
              if ARGV[16]~='' then redis.call('HSET',state,'heading',ARGV[16]) end
              if ARGV[17]~='' then redis.call('HSET',state,'speed',ARGV[17]) end
              local bx,by=tile(tonumber(ARGV[14]),tonumber(ARGV[15]))
              redis.call('GEOADD',geo,ARGV[15],ARGV[14],vehicle)
              redis.call('HINCRBY',counts,bx..':'..by,1)
            end
            local previous=tonumber(redis.call('HGET',state,'ts_epoch_ms') or '-1')
            if previous==tonumber(ARGV[1]) then return tostring(redis.call('HGET',state,'seq') or '0')..'|1' end
            local seq=redis.call('HINCRBY',state,'seq',1)
            local apply=1
            if tonumber(ARGV[1]) < previous then apply=0 end
              if apply==1 then
              local oldlat=redis.call('HGET',state,'lat')
              local oldlon=redis.call('HGET',state,'lon')
              local nx,ny=tile(tonumber(ARGV[3]),tonumber(ARGV[4]))
              local ox,oy=nil,nil
              if oldlat and oldlon then
                ox,oy=tile(tonumber(oldlat),tonumber(oldlon))
                if ox~=nx or oy~=ny then
                  local oldfield=ox..':'..oy
                  local remaining=redis.call('HINCRBY',counts,oldfield,-1)
                  if remaining<=0 then redis.call('HDEL',counts,oldfield) end
                end
              end
              redis.call('HSET',state,'lat',ARGV[3],'lon',ARGV[4],'battery',ARGV[7],'status',ARGV[8],'ts',ARGV[2],'ts_epoch_ms',ARGV[1],'seq',seq)
              if ARGV[5]=='' then redis.call('HDEL',state,'heading') else redis.call('HSET',state,'heading',ARGV[5]) end
              if ARGV[6]=='' then redis.call('HDEL',state,'speed') else redis.call('HSET',state,'speed',ARGV[6]) end
              redis.call('GEOADD',geo,ARGV[4],ARGV[3],vehicle)
              if not oldlat or not oldlon or ox~=nx or oy~=ny then
                redis.call('HINCRBY',counts,nx..':'..ny,1)
              end
            end
            return tostring(seq)..'|'..tostring(apply)
            """,String.class);
    private static final DefaultRedisScript<Long> OFFLINE_SCRIPT=new DefaultRedisScript<>("""
            local state=KEYS[1]; local geo=KEYS[2]; local counts=KEYS[3]
            local vehicle=ARGV[1]; local zoom=tonumber(ARGV[2])
            if redis.call('EXISTS',state)==0 then
              redis.call('HSET',state,'seq',ARGV[3],'lat',ARGV[4],'lon',ARGV[5],'battery',ARGV[6],
                'status',ARGV[7],'ts',ARGV[8],'ts_epoch_ms',ARGV[9])
              if ARGV[10]~='' then redis.call('HSET',state,'heading',ARGV[10]) end
              if ARGV[11]~='' then redis.call('HSET',state,'speed',ARGV[11]) end
              redis.call('GEOADD',geo,ARGV[5],ARGV[4],vehicle)
              local n=2^zoom; local safe=math.max(-85.05112878,math.min(85.05112878,tonumber(ARGV[4])))
              local rad=safe*math.pi/180; local x=math.floor((tonumber(ARGV[5])+180)/360*n)
              local y=math.floor((1-math.log(math.tan(rad)+1/math.cos(rad))/math.pi)/2*n)
              redis.call('HINCRBY',counts,math.max(0,math.min(n-1,x))..':'..math.max(0,math.min(n-1,y)),1)
            end
            if tonumber(redis.call('HGET',state,'ts_epoch_ms') or '0')>tonumber(ARGV[12]) then return 0 end
            if redis.call('HGET',state,'status')=='offline' then return 0 end
            local seq=redis.call('HINCRBY',state,'seq',1)
            redis.call('HSET',state,'status','offline')
            return seq
            """,Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final TelemetryRepository telemetry;
    private final TenantTransaction transactions;
    private final ObjectMapper json;
    private final int zoom;
    public TelemetryLiveRepository(ReactiveStringRedisTemplate redis,TelemetryRepository telemetry,
                                   TenantTransaction transactions,ObjectMapper json,AppProperties properties) {
        this.redis=redis; this.telemetry=telemetry; this.transactions=transactions; this.json=json; this.zoom=properties.map().tileZoom();
    }

    public Mono<TelemetryVehicle> update(TenantIdentity identity,TelemetryBatchRequest.TelemetryEvent event) {
        String key=vehicleKey(identity.tenantId(),event.vehicleId());
        return redis.hasKey(key).flatMap(exists-> {
            Mono<Optional<TelemetryVehicle>> bootstrap=Boolean.TRUE.equals(exists)?Mono.just(Optional.empty()):
                    transactions.inTenantTransaction(()->telemetry.latest(identity.tenantId(),event.vehicleId()).map(Optional::of).defaultIfEmpty(Optional.empty()));
            return bootstrap.flatMap(previous->apply(identity,event,key,previous.orElse(null)));
        });
    }
    public Mono<Void> markOffline(TenantIdentity identity,TelemetryVehicle latest,java.time.Instant cutoff) {
        String key=vehicleKey(identity.tenantId(),latest.vehicleId());
        List<String> args=List.of(latest.vehicleId().toString(),Integer.toString(zoom),Long.toString(latest.seq()),
                Double.toString(latest.lat()),Double.toString(latest.lon()),Integer.toString(latest.batteryPct()),latest.status(),
                latest.ts().toString(),Long.toString(latest.ts().toEpochMilli()),string(latest.headingDeg()),string(latest.speedKmh()),Long.toString(cutoff.toEpochMilli()));
        return redis.execute(OFFLINE_SCRIPT,List.of(key,geoKey(identity.tenantId()),tileCountsKey(identity.tenantId(),zoom)),args.toArray())
                .next().defaultIfEmpty(0L).flatMap(changed->{
                    if(changed==0L)return Mono.empty();
                    TelemetryVehicle state=new TelemetryVehicle(latest.vehicleId(),latest.ts(),changed,latest.lat(),latest.lon(),latest.headingDeg(),latest.speedKmh(),latest.batteryPct(),"offline");
                    return publish(identity,state).then();
                });
    }
    public Mono<Boolean> isOfflineCandidate(TenantIdentity identity,TelemetryVehicle latest,java.time.Instant cutoff) {
        String key=vehicleKey(identity.tenantId(),latest.vehicleId());
        return redis.<String,String>opsForHash().get(key,"ts_epoch_ms")
                .map(Long::parseLong).defaultIfEmpty(latest.ts().toEpochMilli())
                .map(lastSeen->lastSeen<=cutoff.toEpochMilli());
    }
    /** Reads tenant geo members in the world-sized circle, then their current live hashes. */
    public Flux<TelemetryVehicle> snapshot(TenantIdentity identity) {
        Circle world = new Circle(new Point(0, 0), new Distance(20_100, Metrics.KILOMETERS));
        return redis.opsForGeo().radius(geoKey(identity.tenantId()), world,
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeCoordinates())
                .flatMap(result -> {
                    try { return readLiveState(identity.tenantId(), UUID.fromString(result.getContent().getName())); }
                    catch (IllegalArgumentException invalid) { return Mono.empty(); }
                });
    }
    public Mono<Map<String, Long>> clusterCounts(TenantIdentity identity, Set<String> tiles, int viewportZoom) {
        return redis.<String, String>opsForHash().entries(tileCountsKey(identity.tenantId(), zoom))
                .<Map.Entry<String,Long>>handle((entry, sink) -> {
                    String[] coordinates=entry.getKey().split(":",2);
                    int x=Integer.parseInt(coordinates[0]), y=Integer.parseInt(coordinates[1]);
                    int shift=Math.max(0,zoom-viewportZoom);
                    String parent=(x>>shift)+":"+(y>>shift);
                    if(tiles.contains(parent))sink.next(Map.entry(parent,Long.parseLong(entry.getValue())));
                })
                .collect(HashMap<String,Long>::new,(counts,entry)->counts.merge(entry.getKey(),entry.getValue(),Long::sum));
    }
    public static String tileFor(double lat, double lon, int zoom) {
        int n = 1 << zoom;
        int x = Math.min(n - 1, Math.max(0, (int)Math.floor((lon + 180) / 360 * n)));
        double safe = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double rad = Math.toRadians(safe);
        int y = Math.min(n - 1, Math.max(0, (int)Math.floor((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * n)));
        return x + ":" + y;
    }
    private Mono<TelemetryVehicle> readLiveState(UUID tenant, UUID vehicle) {
        return redis.<String, String>opsForHash().entries(vehicleKey(tenant, vehicle)).collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .filter(values -> values.containsKey("lat") && values.containsKey("lon"))
                .map(values -> new TelemetryVehicle(vehicle, OffsetDateTime.parse(values.get("ts")).toInstant(),
                        Long.parseLong(values.getOrDefault("seq", "0")), Double.parseDouble(values.get("lat")),
                        Double.parseDouble(values.get("lon")), parseFloat(values.get("heading")), parseFloat(values.get("speed")),
                        Integer.parseInt(values.getOrDefault("battery", "0")), values.getOrDefault("status", "unknown")));
    }
    private static Float parseFloat(String value) { return value == null || value.isBlank() ? null : Float.valueOf(value); }
    private Mono<TelemetryVehicle> apply(TenantIdentity identity,TelemetryBatchRequest.TelemetryEvent e,String key,TelemetryVehicle previous) {
        String heading=e.headingDeg()==null?"":e.headingDeg().toString();
        String speed=e.speedKmh()==null?"":e.speedKmh().toString();
        List<String> args=List.of(Long.toString(e.ts().toEpochMilli()),e.ts().toString(),Double.toString(e.lat()),Double.toString(e.lon()),heading,speed,
                Integer.toString(e.batteryPct()),e.status(),e.vehicleId().toString(),Integer.toString(zoom),
                previous==null?"":Long.toString(previous.seq()),previous==null?"":Long.toString(previous.ts().toEpochMilli()),
                previous==null?"":previous.ts().toString(),previous==null?"":Double.toString(previous.lat()),
                previous==null?"":Double.toString(previous.lon()),previous==null?"":string(previous.headingDeg()),
                previous==null?"":string(previous.speedKmh()),previous==null?"":Integer.toString(previous.batteryPct()),previous==null?"":previous.status());
        return redis.execute(UPDATE_SCRIPT,List.of(key,geoKey(identity.tenantId()),tileCountsKey(identity.tenantId(),zoom)),args.toArray())
                .next().switchIfEmpty(Mono.error(new IllegalStateException("Redis live-state script returned no result")))
                .flatMap(result->{
                    String[] values=result.split("\\|",2); long seq=Long.parseLong(values[0]); boolean applied="1".equals(values[1]);
                    TelemetryVehicle state=new TelemetryVehicle(e.vehicleId(),e.ts(),seq,e.lat(),e.lon(),e.headingDeg(),e.speedKmh(),e.batteryPct(),e.status());
                    if(!applied) return Mono.just(state);
                    return publish(identity,state).thenReturn(state);
                });
    }
    private Mono<Long> publish(TenantIdentity identity,TelemetryVehicle state) {
        Map<String,Object> payload=new LinkedHashMap<>(); payload.put("vehicleId",state.vehicleId());payload.put("ts",state.ts());payload.put("seq",state.seq());payload.put("lat",state.lat());payload.put("lon",state.lon());payload.put("heading",state.headingDeg());payload.put("speed",state.speedKmh());payload.put("battery",state.batteryPct());payload.put("status",state.status());
        return redis.convertAndSend("t:"+identity.tenantId()+":ch:updates",json.writeValueAsString(payload));
    }
    public static String vehicleKey(UUID tenant,UUID vehicle) { return "t:"+tenant+":v:"+vehicle; }
    public static String geoKey(UUID tenant) { return "t:"+tenant+":geo"; }
    public static String tileCountsKey(UUID tenant,int zoom) { return "t:"+tenant+":tc:"+zoom; }
    public static String alertChannel(UUID tenant) { return "t:"+tenant+":ch:alerts"; }
    private static String string(Float value) { return value==null?"":value.toString(); }
}
