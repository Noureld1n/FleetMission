package com.fleetcontrol.backend.service;

import com.fleetcontrol.backend.dto.response.AlertResponse;
import com.fleetcontrol.backend.dto.response.FleetMetricsResponse;
import com.fleetcontrol.backend.exception.ApiException;
import com.fleetcontrol.backend.mapper.DomainMapper;
import com.fleetcontrol.backend.repository.DomainRepository;
import com.fleetcontrol.backend.repository.TelemetryLiveRepository;
import com.fleetcontrol.backend.security.TenantIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Per-connection SSE sources; Redis and database subscriptions are cancelled with the response. */
@Service
public class EventStreamService {
    private static final int ALERT_REPLAY_PAGE_SIZE=256;
    private static final Duration METRICS_POLL_INTERVAL=Duration.ofSeconds(15);
    private static final Duration HEARTBEAT_INTERVAL=Duration.ofSeconds(15);

    private final ReactiveRedisMessageListenerContainer redisListener;
    private final DomainRepository repository;
    private final TenantTransaction transactions;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry meters;
    private final AtomicLong activeConnections=new AtomicLong();

    public EventStreamService(ReactiveRedisMessageListenerContainer redisListener,DomainRepository repository,
            TenantTransaction transactions,ObjectMapper json,Clock clock,MeterRegistry meters) {
        this.redisListener=redisListener;this.repository=repository;this.transactions=transactions;
        this.json=json;this.clock=clock;this.meters=meters;
        meters.gauge("sse.connections.active",activeConnections);
        meters.counter("sse.connections.opened");meters.counter("sse.connections.disconnected");
        meters.counter("sse.connections.errors");meters.counter("sse.buffer.overflows");
        meters.counter("sse.alerts.replayed");meters.counter("sse.alerts.live");meters.counter("sse.metrics.sent");
    }

    public Flux<ServerSentEvent<Object>> stream(String lastEventId) {
        Instant since=parseLastEventId(lastEventId);
        return DomainSupport.identity().flatMapMany(identity->{
            Instant replayCutoff=clock.instant();
            Flux<ServerSentEvent<Object>> replay=since==null?Flux.empty():replayAlerts(identity,since,replayCutoff)
                    .map(this::alertEvent).doOnNext(event->meters.counter("sse.alerts.replayed").increment());
            Flux<ServerSentEvent<Object>> alerts=redisListener.receive(ChannelTopic.of(TelemetryLiveRepository.alertChannel(identity.tenantId())))
                    .map(message->json.readValue(message.getMessage(),AlertResponse.class))
                    .map(this::alertEvent).doOnNext(event->meters.counter("sse.alerts.live").increment());
            Flux<ServerSentEvent<Object>> metrics=metricEvents(identity);
            Flux<ServerSentEvent<Object>> heartbeats=Flux.interval(HEARTBEAT_INTERVAL)
                    .map(tick->ServerSentEvent.<Object>builder().event("heartbeat").build());
            return Flux.merge(replay,alerts,metrics,heartbeats)
                    .onBackpressureBuffer(256,dropped->meters.counter("sse.buffer.overflows").increment())
                    .doOnSubscribe(subscription->{activeConnections.incrementAndGet();meters.counter("sse.connections.opened").increment();})
                    .doFinally(signal->{
                        activeConnections.decrementAndGet();
                        if(signal==reactor.core.publisher.SignalType.CANCEL||signal==reactor.core.publisher.SignalType.ON_COMPLETE)
                            meters.counter("sse.connections.disconnected").increment();
                        else if(signal==reactor.core.publisher.SignalType.ON_ERROR)meters.counter("sse.connections.errors").increment();
                    });
        });
    }

    private Flux<AlertResponse> replayAlerts(TenantIdentity identity,Instant since,Instant cutoff) {
        OffsetDateTime sinceUtc=since.atOffset(ZoneOffset.UTC);
        OffsetDateTime untilUtc=cutoff.atOffset(ZoneOffset.UTC);
        return readReplayPage(identity,sinceUtc,untilUtc,null,null)
                .expand(page->{
                    if(page.alerts().size()<ALERT_REPLAY_PAGE_SIZE)return Mono.empty();
                    AlertResponse last=page.alerts().get(page.alerts().size()-1);
                    return readReplayPage(identity,sinceUtc,untilUtc,last.updatedAt(),last.id());
                },1)
                .concatMapIterable(ReplayPage::alerts);
    }

    private Mono<ReplayPage> readReplayPage(TenantIdentity identity,OffsetDateTime since,OffsetDateTime until,
                                            OffsetDateTime cursorAt,UUID cursorId) {
        return transactions.inTenantTransaction(()->repository.alertChangesPage(since,until,cursorAt,cursorId,ALERT_REPLAY_PAGE_SIZE).collectList())
                .contextWrite(Context.of(TenantIdentity.class,identity)).map(ReplayPage::new);
    }

    private Flux<ServerSentEvent<Object>> metricEvents(TenantIdentity identity) {
        Flux<ServerSentEvent<Object>> initial=Mono.defer(()->latestFleetMetric(identity)).flux();
        Flux<ServerSentEvent<Object>> polling=Flux.interval(METRICS_POLL_INTERVAL)
                .concatMap(tick->latestFleetMetric(identity));
        return Flux.concat(initial,polling)
                .doOnNext(event->meters.counter("sse.metrics.sent").increment());
    }

    private Mono<ServerSentEvent<Object>> latestFleetMetric(TenantIdentity identity) {
        Instant now=clock.instant();
        OffsetDateTime from=now.minus(Duration.ofMinutes(2)).atOffset(ZoneOffset.UTC);
        OffsetDateTime to=now.atOffset(ZoneOffset.UTC);
        return transactions.inTenantTransaction(()->repository.fleetHistory(from,to,3).next()) // Changed .last() to .next()
                .contextWrite(Context.of(TenantIdentity.class,identity))
                .map(DomainMapper::toFleetMetricsPoint)
                .map(point->ServerSentEvent.<Object>builder(point).event("fleet.metrics").build());
    }

    private ServerSentEvent<Object> alertEvent(AlertResponse alert) {
        String eventName="open".equals(alert.status())&&alert.updatedAt().toInstant().equals(alert.openedAt().toInstant())
                ?"alert.opened":"alert.updated";
        return ServerSentEvent.<Object>builder(alert)
                .id(alert.updatedAt().toInstant().toString())
                .event(eventName)
                .build();
    }

    private static Instant parseLastEventId(String value) {
        if(value==null||value.isBlank())return null;
        try{return Instant.parse(value);}
        catch(RuntimeException invalid){throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_LAST_EVENT_ID","Last-Event-ID must be an ISO-8601 UTC timestamp.");}
    }
    private record ReplayPage(List<AlertResponse> alerts) { private ReplayPage { alerts=List.copyOf(alerts); } }
}
