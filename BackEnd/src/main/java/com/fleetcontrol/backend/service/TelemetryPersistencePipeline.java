package com.fleetcontrol.backend.service;

import com.fleetcontrol.backend.config.AppProperties;
import com.fleetcontrol.backend.dto.request.TelemetryBatchRequest;
import com.fleetcontrol.backend.dto.response.AlertResponse;
import com.fleetcontrol.backend.exception.ApiException;
import com.fleetcontrol.backend.model.QueuedTelemetryBatch;
import com.fleetcontrol.backend.model.TelemetryVehicle;
import com.fleetcontrol.backend.repository.DomainRepository;
import com.fleetcontrol.backend.repository.TelemetryRepository;
import com.fleetcontrol.backend.repository.TelemetryLiveRepository;
import com.fleetcontrol.backend.security.TenantIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.context.SmartLifecycle;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;
import reactor.util.context.Context;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, lifecycle-owned persistence/recovery pipeline. */
@Component
public class TelemetryPersistencePipeline implements SmartLifecycle {
    private static final Logger log=LoggerFactory.getLogger(TelemetryPersistencePipeline.class);
    private static final DefaultRedisScript<Long> PUSH_RECOVERY=new DefaultRedisScript<>("""
            local length=redis.call('LLEN',KEYS[1])
            if length>=tonumber(ARGV[2]) then return 0 end
            redis.call('RPUSH',KEYS[1],ARGV[1])
            return length+1
            """,Long.class);
    private final Sinks.Many<QueuedTelemetryBatch> sink;
    private final TelemetryRepository telemetry;
    private final DomainRepository domain;
    private final TelemetryLiveRepository liveState;
    private final TenantTransaction transactions;
    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper json;
    private final AppProperties properties;
    private final MeterRegistry meters;
    private final AtomicLong outstandingEvents=new AtomicLong();
    private final AtomicBoolean recoveryDiscoveryPending=new AtomicBoolean(true);
    private final AtomicLong queueCapacity;
    private final Map<UUID,TenantIdentity> knownTenants=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID,java.util.concurrent.ConcurrentLinkedQueue<QueuedTelemetryBatch>> heldBatches=new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean running;
    private reactor.core.Disposable subscription;

    public TelemetryPersistencePipeline(TelemetryRepository telemetry,DomainRepository domain,TelemetryLiveRepository liveState,TenantTransaction transactions,
            ReactiveStringRedisTemplate redis,ObjectMapper json,AppProperties properties,MeterRegistry meters) {
        this.telemetry=telemetry;this.domain=domain;this.liveState=liveState;this.transactions=transactions;this.redis=redis;this.json=json;this.properties=properties;this.meters=meters;
        this.sink=Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(properties.ingestion().queueCapacity()));
        this.queueCapacity=new AtomicLong(properties.ingestion().queueCapacity());
        meters.gauge("ingest.queue.depth",outstandingEvents);
        meters.gauge("ingest.queue.capacity",queueCapacity,AtomicLong::get);
        meters.counter("ingest.events.accepted"); meters.counter("ingest.events.persisted");
        meters.counter("ingest.events.rejected"); meters.counter("ingest.queue.dropped");
        meters.counter("ingest.persistence.retries"); meters.counter("ingest.persistence.terminal_failures");
        meters.summary("ingest.batch.size"); meters.timer("ingest.batch.write");
    }

    public Reservation reserve(int eventCount) {
        long capacity=queueCapacity.get();
        while(true) {
            long current=outstandingEvents.get();
            if(current+eventCount>capacity) {
                meters.counter("ingest.queue.dropped").increment();
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"INGEST_QUEUE_FULL","The ingestion queue is full; retry this batch.");
            }
            if(outstandingEvents.compareAndSet(current,current+eventCount)) return new Reservation(eventCount);
        }
    }
    public void offer(Reservation reservation,QueuedTelemetryBatch batch) {
        knownTenants.put(batch.identity().tenantId(),batch.identity());
        Sinks.EmitResult result=sink.tryEmitNext(batch);
        if(result.isFailure()) {
            meters.counter("ingest.queue.dropped").increment(); reservation.release();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"INGEST_QUEUE_FULL","The ingestion queue is full; retry this batch.");
        }
        reservation.commit();
        meters.counter("ingest.events.accepted").increment(batch.events().size());
    }

    @Override public synchronized void start() {
        if(running)return;
        Flux<QueuedEvent> events=sink.asFlux().flatMapIterable(batch->{
            List<QueuedEvent> flattened=new ArrayList<>(batch.events().size());
            for(int i=0;i<batch.events().size();i++) flattened.add(new QueuedEvent(batch.identity(),batch.events().get(i),batch.sequences().get(i)));
            return flattened;
        });
        Flux<Void> writes=events.groupBy(e->e.identity().tenantId())
                .flatMap(group->group.bufferTimeout(properties.ingestion().batchSize(),properties.ingestion().maxWait())
                        .filter(batch->!batch.isEmpty()).concatMap(this::persistEvents),8);
        Flux<Void> recovery=Flux.interval(Duration.ofSeconds(15)).concatMap(tick->recoverOnePerTenant()
                .onErrorResume(error->{log.error("Telemetry recovery scan failed",error);return Mono.empty();}));
        Flux<Void> heldRetry=Flux.interval(Duration.ofSeconds(30)).concatMap(tick->retryHeldBatches()
                .onErrorResume(error->{log.error("Held telemetry retry failed",error);return Mono.empty();}));
        Duration sweepInterval=properties.alerts().offlineAfter().dividedBy(4);
        if(sweepInterval.isZero())sweepInterval=Duration.ofSeconds(1);
        if(sweepInterval.compareTo(Duration.ofSeconds(30))>0)sweepInterval=Duration.ofSeconds(30);
        Flux<Void> offlineSweep=Flux.interval(sweepInterval).concatMap(tick->sweepOfflineVehicles()
                .onErrorResume(error->{log.error("Offline alert sweep failed",error);return Mono.empty();}));
        subscription=Flux.merge(writes,recovery,heldRetry,offlineSweep).name("telemetry-persistence-pipeline").checkpoint("telemetry-persistence-pipeline-root")
                .subscribe(ignored->{},error->log.error("Telemetry pipeline terminated",error));
        running=true;
    }
    private Mono<Void> persistEvents(List<QueuedEvent> events) {
        QueuedTelemetryBatch batch=new QueuedTelemetryBatch(events.get(0).identity(),
                events.stream().map(QueuedEvent::event).toList(),events.stream().map(QueuedEvent::seq).toList());
        return persist(batch,true).doOnNext(stored->{
            if(stored) outstandingEvents.addAndGet(-batch.events().size());
            else heldBatches.computeIfAbsent(batch.identity().tenantId(),ignored->new java.util.concurrent.ConcurrentLinkedQueue<>()).offer(batch);
        }).then();
    }
    /** true means committed to PostgreSQL or safely transferred to tenant recovery storage. */
    private Mono<Boolean> persist(QueuedTelemetryBatch batch,boolean allowRecoveryTransfer) {
        Timer.Sample timer=Timer.start(meters);
        return Mono.defer(()->persistInTransaction(batch))
                .retryWhen(Retry.backoff(5,Duration.ofMillis(250)).maxBackoff(Duration.ofSeconds(10))
                        .filter(TelemetryPersistencePipeline::retryable)
                        .doBeforeRetry(retry->meters.counter("ingest.persistence.retries").increment()))
                .thenReturn(Boolean.TRUE)
                .doOnNext(stored->{meters.counter("ingest.events.persisted").increment(batch.events().size());meters.summary("ingest.batch.size").record(batch.events().size());})
                .onErrorResume(error->{
                    if(allowRecoveryTransfer)return saveForRecovery(batch,error);
                    meters.counter("ingest.persistence.terminal_failures").increment();
                    log.error("Telemetry recovery batch is still pending; tenant={}, events={}",batch.identity().tenantId(),batch.events().size(),error);
                    return Mono.just(Boolean.FALSE);
                })
                .doFinally(signal->timer.stop(meters.timer("ingest.batch.write")));
    }
    private Mono<Void> persistInTransaction(QueuedTelemetryBatch batch) {
        UUID tenant=batch.identity().tenantId();
        List<UUID> vehicleIds=batch.events().stream().map(TelemetryBatchRequest.TelemetryEvent::vehicleId).distinct().toList();
        List<Long> seqs=batch.sequences();
        return transactions.inTenantTransaction(()->telemetry.persistBatch(tenant,batch.events(),seqs)
                .then(telemetry.refreshVehicleStatuses(tenant,vehicleIds))
                .thenMany(telemetry.latestForVehicles(tenant,vehicleIds).concatMap(latest->evaluateAlerts(tenant,latest)))
                .collectList())
                .contextWrite(Context.of(TenantIdentity.class,batch.identity()))
                .flatMap(alerts->Flux.fromIterable(alerts).concatMap(alert->redis.convertAndSend(
                        TelemetryLiveRepository.alertChannel(tenant),json.writeValueAsString(alert))).then());
    }
    private Flux<AlertResponse> evaluateAlerts(UUID tenant,TelemetryVehicle event) {
        int threshold=properties.alerts().batteryThreshold();
        String batteryDetail=json.writeValueAsString(Map.of("battery_pct",event.batteryPct(),"observed_at",event.ts()));
        Mono<AlertResponse> battery=event.batteryPct()<=threshold
                ?domain.openOrUpdateVehicleAlert(tenant,event.vehicleId(),"low_battery",event.batteryPct()<=threshold/2?"critical":"warning",batteryDetail)
                :domain.resolveVehicleAlert(event.vehicleId(),"low_battery");
        boolean offline="offline".equals(event.status())||event.ts().isBefore(Instant.now().minus(properties.alerts().offlineAfter()));
        String offlineDetail=json.writeValueAsString(Map.of("last_seen",event.ts()));
        Mono<AlertResponse> offlineAlert=offline
                ?domain.openOrUpdateVehicleAlert(tenant,event.vehicleId(),"vehicle_offline","warning",offlineDetail)
                :domain.resolveVehicleAlert(event.vehicleId(),"vehicle_offline");
        return Flux.concat(battery.flux(),offlineAlert.flux());
    }
    private Mono<Boolean> saveForRecovery(QueuedTelemetryBatch batch,Throwable error) {
        meters.counter("ingest.persistence.terminal_failures").increment();
        String key=recoveryKey(batch.identity().tenantId());
        String payload=json.writeValueAsString(batch);
        return redis.execute(PUSH_RECOVERY,List.of(key),payload,Integer.toString(properties.ingestion().queueCapacity()))
                .next().flatMap(size->{
                    if(size==0L) { meters.counter("ingest.recovery.queue.full").increment(); return Mono.<Boolean>error(new IllegalStateException("Tenant recovery queue is full")); }
                    return Mono.just(Boolean.TRUE);
                })
                .doOnNext(stored->{if(stored)log.error("Telemetry batch moved to recovery queue after retries; tenant={}, events={}",batch.identity().tenantId(),batch.events().size(),error);})
                .onErrorResume(recoveryError->{
                    log.error("Telemetry batch could not be persisted or placed in Redis recovery queue; tenant={}, events={}",batch.identity().tenantId(),batch.events().size(),recoveryError);
                    return Mono.just(Boolean.FALSE);
                });
    }
    private Mono<Void> recoverOnePerTenant() {
        Mono<Void> discovery=Mono.defer(()->{
            if(!recoveryDiscoveryPending.compareAndSet(true,false))return Mono.empty();
            return redis.scan(ScanOptions.scanOptions().match("t:*:ingest:recovery").count(200).build())
                    .map(TelemetryPersistencePipeline::tenantFromRecoveryKey).filter(Objects::nonNull)
                    .doOnNext(tenant->knownTenants.putIfAbsent(tenant,new TenantIdentity(new UUID(0,0),tenant,"ingest")))
                    .then().doOnError(error->recoveryDiscoveryPending.set(true));
        });
        return discovery.thenMany(Flux.fromIterable(knownTenants.values()).distinct(TenantIdentity::tenantId))
                .flatMap(identity->{
                    String key=recoveryKey(identity.tenantId());
                    return redis.opsForList().index(key,0).flatMap(raw->decode(raw).flatMap(batch->persist(batch,false)
                            .flatMap(stored->stored?redis.opsForList().remove(key,1,raw).then():Mono.empty())));
                },4).then();
    }
    private Mono<Void> retryHeldBatches() {
        return Flux.fromIterable(heldBatches.entrySet()).flatMap(entry->Mono.defer(()->{
            QueuedTelemetryBatch batch=entry.getValue().peek();
            if(batch==null)return Mono.empty();
            return persist(batch,true).doOnNext(stored->{
                if(stored&&entry.getValue().remove(batch))outstandingEvents.addAndGet(-batch.events().size());
            }).then();
        }),4).then();
    }
    private Mono<Void> sweepOfflineVehicles() {
        Instant cutoff=Instant.now().minus(properties.alerts().offlineAfter());
        return Flux.fromIterable(knownTenants.values()).distinct(TenantIdentity::tenantId).flatMap(identity->{
            UUID tenant=identity.tenantId();
            return transactions.<List<TelemetryVehicle>>inTenantTransaction(()->telemetry.latestForTenant(tenant).collectList())
                    .contextWrite(Context.of(TenantIdentity.class,identity))
                    .flatMap(latestRows->Flux.fromIterable(latestRows).filterWhen(latest->liveState.isOfflineCandidate(identity,latest,cutoff)
                            .map(isStale->isStale&& (latest.ts().isBefore(cutoff)||"offline".equals(latest.status())))).collectList()
                    .flatMap(stale->transactions.<List<Map.Entry<TelemetryVehicle,AlertResponse>>>inTenantTransaction(()->{
                        List<UUID> ids=stale.stream().map(TelemetryVehicle::vehicleId).toList();
                        return telemetry.markVehiclesOffline(tenant,ids).thenMany(Flux.fromIterable(stale).concatMap(latest->
                                domain.openOrUpdateVehicleAlert(tenant,latest.vehicleId(),"vehicle_offline","warning",
                                        json.writeValueAsString(Map.of("last_seen",latest.ts()))).map(alert->Map.entry(latest,alert))))
                                .collectList();
                    }).contextWrite(Context.of(TenantIdentity.class,identity))))
                    .flatMapMany(changed->Flux.fromIterable(changed)
                    .concatMap(pair->liveState.markOffline(identity,pair.getKey(),cutoff)
                            .then(redis.convertAndSend(TelemetryLiveRepository.alertChannel(tenant),json.writeValueAsString(pair.getValue())))).then());
        },4).then();
    }
    private Mono<QueuedTelemetryBatch> decode(String raw) {
        return Mono.fromCallable(()->json.readValue(raw,QueuedTelemetryBatch.class));
    }
    private static boolean retryable(Throwable e) {
        String name=e.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if(e instanceof ApiException || name.contains("integrity") || name.contains("badgrammar") || name.contains("nontransient")) return false;
        return name.contains("transient") || name.contains("timeout") || name.contains("connectionfailure") || name.contains("redisconnection");
    }
    private static String recoveryKey(UUID tenant) { return "t:"+tenant+":ingest:recovery"; }
    private static UUID tenantFromRecoveryKey(String key) {
        String[] parts=key.split(":",4);
        if(parts.length!=4||!"t".equals(parts[0])||!"ingest".equals(parts[2])||!"recovery".equals(parts[3]))return null;
        try{return UUID.fromString(parts[1]);}catch(IllegalArgumentException invalid){return null;}
    }
    @Override public synchronized void stop() { if(subscription!=null)subscription.dispose();running=false; }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE-100; }
    @Override public boolean isAutoStartup() { return true; }
    private record QueuedEvent(TenantIdentity identity,TelemetryBatchRequest.TelemetryEvent event,long seq) {}
    public final class Reservation {
        private final int eventCount;
        private final AtomicBoolean committed=new AtomicBoolean();
        private final AtomicBoolean released=new AtomicBoolean();
        private Reservation(int eventCount) { this.eventCount=eventCount; }
        private void commit() { committed.set(true); }
        public void releaseIfUnqueued() { if(!committed.get()) release(); }
        private void release() { if(released.compareAndSet(false,true)) outstandingEvents.addAndGet(-eventCount); }
    }
}
