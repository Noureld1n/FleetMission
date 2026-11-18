package com.fleetcontrol.backend.controller;

import com.fleetcontrol.backend.config.AppProperties;
import com.fleetcontrol.backend.dto.message.MapSocketMessage;
import com.fleetcontrol.backend.dto.message.MapSocketMessage.Inbound;
import com.fleetcontrol.backend.dto.message.MapSocketMessage.Outbound;
import com.fleetcontrol.backend.dto.message.MapSocketMessage.Viewport;
import com.fleetcontrol.backend.model.TelemetryVehicle;
import com.fleetcontrol.backend.repository.TelemetryLiveRepository;
import com.fleetcontrol.backend.security.TenantIdentity;
import com.fleetcontrol.backend.security.WebSocketJwtAuthenticator;
import com.fleetcontrol.backend.service.MapTileRegistry;
import com.fleetcontrol.backend.service.MapUpdateDispatcher;
import com.fleetcontrol.backend.util.SlippyMapTiles;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authenticated tenant-scoped live map socket. */
@Component
public class MapWebSocketHandler implements WebSocketHandler {
    private static final int MAX_PENDING_VEHICLES = 4096;
    private final WebSocketJwtAuthenticator authenticator;
    private final TelemetryLiveRepository live;
    private final MapUpdateDispatcher updateDispatcher;
    private final ObjectMapper json;
    private final AppProperties properties;
    private final MeterRegistry meters;
    private final MapTileRegistry registry;
    private final AtomicLong open = new AtomicLong();
    private final AtomicLong tileSubscriptions = new AtomicLong();

    public MapWebSocketHandler(WebSocketJwtAuthenticator authenticator, TelemetryLiveRepository live,
            MapUpdateDispatcher updateDispatcher, ObjectMapper json,
            AppProperties properties, MeterRegistry meters, MapTileRegistry registry) {
        this.authenticator=authenticator; this.live=live; this.updateDispatcher=updateDispatcher;
        this.json=json; this.properties=properties; this.meters=meters; this.registry=registry;
        meters.gauge("map.websocket.connections.active", open);
        meters.gauge("map.websocket.tiles.subscribed", tileSubscriptions);
        meters.counter("map.websocket.connections.opened"); meters.counter("map.websocket.connections.closed");
        meters.counter("map.websocket.connections.errors"); meters.counter("map.websocket.updates.conflated");
        meters.counter("map.websocket.flush.messages");
    }

    @Override public Mono<Void> handle(WebSocketSession session) {
        Sinks.Many<WebSocketMessage> input=Sinks.many().multicast().onBackpressureBuffer(64,false);
        Flux<WebSocketMessage> frames=input.asFlux();
        Mono<Void> wire=session.receive().doOnNext(frame->input.tryEmitNext(frame))
                .doOnComplete(input::tryEmitComplete).doOnError(input::tryEmitError).then();
        Mono<Inbound> first = frames.next().timeout(properties.map().authTimeout())
                .map(message -> json.readValue(message.getPayloadAsText(), Inbound.class));
        Mono<Void> protocol=first.flatMapMany(auth -> {
            if (!"auth".equals(auth.type()) || auth.token()==null || auth.token().isBlank())
                return Flux.error(new IllegalArgumentException("First message must be auth with a JWT token."));
            return authenticator.authenticateFirstAuthMessage(Mono.just(auth.token()))
                    .flatMapMany(identity -> authenticated(session, frames.skip(1), identity));
        }).then();
        return Mono.when(wire,protocol).onErrorResume(error -> closeForFailure(session,error));
    }

    private Mono<Void> authenticated(WebSocketSession session, Flux<WebSocketMessage> inbound, TenantIdentity identity) {
        Sinks.Many<Outbound> outbound = Sinks.many().unicast().onBackpressureBuffer(new java.util.concurrent.ArrayBlockingQueue<>(256));
        ConcurrentHashMap<UUID, TelemetryVehicle> pending = new ConcurrentHashMap<>();
        Set<String> tiles = ConcurrentHashMap.newKeySet();
        UUID connection=UUID.randomUUID();
        AtomicLong viewportVersion = new AtomicLong(-1);
        AtomicInteger viewportZoom = new AtomicInteger(properties.map().tileZoom());
        AtomicBoolean snapshotInProgress = new AtomicBoolean();
        outbound.tryEmitNext(Outbound.ready());
        Flux<Outbound> updates = updateDispatcher.updates(identity.tenantId())
                .filter(vehicle -> registry.contains(connection,identity.tenantId(),
                        TelemetryLiveRepository.tileFor(vehicle.lat(), vehicle.lon(), viewportZoom.get())))
                .doOnNext(vehicle -> pending.compute(vehicle.vehicleId(), (id, previous) -> {
                    if (previous != null && previous.seq() > vehicle.seq()) return previous;
                    if (previous != null) meters.counter("map.websocket.updates.conflated").increment();
                    else if (pending.size() >= MAX_PENDING_VEHICLES) {
                        meters.counter("map.websocket.updates.dropped").increment();
                        return null;
                    }
                    return vehicle;
                }))
                .thenMany(Flux.<Outbound>empty());
        Flux<Outbound> flushes = Flux.interval(properties.map().flushInterval())
                .filter(tick -> !snapshotInProgress.get() && !pending.isEmpty())
                .map(tick -> {
                    io.micrometer.core.instrument.DistributionSummary.builder("map.websocket.queue.size")
                            .register(meters).record(pending.size());
                    List<TelemetryVehicle> batch = new ArrayList<>(pending.values()); pending.clear();
                    meters.counter("map.websocket.flush.messages").increment();
                    return Outbound.delta(Math.max(0, viewportVersion.get()), batch);
                });
        Flux<WebSocketMessage> sending = outbound.asFlux().mergeWith(flushes).mergeWith(updates)
                .map(message -> session.textMessage(json.writeValueAsString(message)));
        Mono<Void> send = session.send(sending);
        Mono<Void> receive = inbound.concatMap(message -> process(session, identity,
                        json.readValue(message.getPayloadAsText(), Inbound.class), tiles, viewportVersion,
                        viewportZoom, connection, snapshotInProgress, outbound, pending))
                .then();
        open.incrementAndGet(); meters.counter("map.websocket.connections.opened").increment();
        return Flux.<Void>merge(send, receive).doFinally(signal -> {
            open.decrementAndGet(); meters.counter("map.websocket.connections.closed").increment();
            registry.removeAll(connection,identity.tenantId()); tileSubscriptions.addAndGet(-tiles.size());
            tiles.clear(); pending.clear(); outbound.tryEmitComplete();
        }).then();
    }

    private Mono<Void> process(WebSocketSession session, TenantIdentity identity, Inbound message, Set<String> tiles,
            AtomicLong version, AtomicInteger viewportZoom, UUID connection,
            AtomicBoolean snapshotInProgress,
            Sinks.Many<Outbound> outbound, Map<UUID, TelemetryVehicle> pending) {
        if (!"viewport".equals(message.type()) || message.viewport()==null)
            return emit(outbound, Outbound.error("INVALID_MESSAGE", "Expected a viewport message."));
        Viewport viewport=message.viewport();
        final Set<String> next;
        try { next=SlippyMapTiles.forViewport(viewport, properties.map().tileZoom()); }
        catch (IllegalArgumentException invalid) { return emit(outbound, Outbound.error("INVALID_VIEWPORT", invalid.getMessage())); }
        version.set(viewport.viewportVersion());
        Set<String> previous=Set.copyOf(tiles);
        registry.replace(connection,identity.tenantId(),previous,next);
        tiles.clear(); tiles.addAll(next);
        viewportZoom.set(Math.min(viewport.zoom(),properties.map().tileZoom()));
        tileSubscriptions.addAndGet(next.size()-previous.size());
        pending.clear();
        snapshotInProgress.set(true);
        long started=System.nanoTime();
        boolean lowZoom=viewport.zoom()<properties.map().tileZoom();
        Mono<Outbound> snapshot=lowZoom
                ? live.clusterCounts(identity,next,viewportZoom.get()).map(counts->Outbound.clusters(viewport.viewportVersion(),counts))
                : live.snapshot(identity).filter(vehicle->next.contains(TelemetryLiveRepository.tileFor(
                        vehicle.lat(),vehicle.lon(),properties.map().tileZoom())))
                        .collectList().map(vehicles->Outbound.snapshot(viewport.viewportVersion(),vehicles));
        return snapshot.doOnSuccess(value->io.micrometer.core.instrument.Timer.builder("map.websocket.snapshot.duration")
                        .register(meters).record(System.nanoTime()-started, java.util.concurrent.TimeUnit.NANOSECONDS))
                .flatMap(value->{
                    if (value.type().equals("snapshot")) {
                        Map<UUID,TelemetryVehicle> merged=new HashMap<>();
                        value.vehicles().forEach(vehicle->merged.put(vehicle.vehicleId(),vehicle));
                        pending.forEach((id,update)->merged.merge(id,update,(oldState,newState)->
                                newState.seq()>oldState.seq()?newState:oldState));
                        pending.clear();
                        value=Outbound.snapshot(viewport.viewportVersion(),List.copyOf(merged.values()));
                    }
                    Mono<Void> emitted=emit(outbound,value);
                    return emitted.doFinally(signal->snapshotInProgress.set(false));
                }).doOnError(error->snapshotInProgress.set(false));
    }

    private static Mono<Void> emit(Sinks.Many<Outbound> sink, Outbound message) {
        Sinks.EmitResult result=sink.tryEmitNext(message);
        return result.isSuccess()?Mono.empty():Mono.error(new IllegalStateException("WebSocket output buffer is unavailable."));
    }

    private Mono<Void> closeForFailure(WebSocketSession session, Throwable error) {
        meters.counter("map.websocket.connections.errors").increment();
        CloseStatus status = error instanceof IllegalArgumentException
                || error instanceof org.springframework.security.authentication.BadCredentialsException
                ? CloseStatus.POLICY_VIOLATION : CloseStatus.NOT_ACCEPTABLE;
        return session.close(status);
    }
}
