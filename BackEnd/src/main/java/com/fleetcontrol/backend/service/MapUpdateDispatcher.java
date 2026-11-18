package com.fleetcontrol.backend.service;

import com.fleetcontrol.backend.model.TelemetryVehicle;
import com.fleetcontrol.backend.repository.TelemetryLiveRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Shares one tenant-wide Redis pub/sub subscription across local map sockets. */
@Component
public class MapUpdateDispatcher {
    private final ReactiveRedisMessageListenerContainer listener;
    private final ObjectMapper json;
    private final MeterRegistry meters;
    private final ConcurrentHashMap<UUID,Channel> channels=new ConcurrentHashMap<>();
    public MapUpdateDispatcher(ReactiveRedisMessageListenerContainer listener,ObjectMapper json,MeterRegistry meters) {
        this.listener=listener;this.json=json;this.meters=meters;
    }
    public Flux<TelemetryVehicle> updates(UUID tenant) {
        return Flux.defer(()->{
            Channel channel=channels.compute(tenant,(id,current)->{
                if(current==null||current.closed)return new Channel(id);
                current.clients.incrementAndGet(); return current;
            });
            return channel.events.asFlux().doFinally(signal->release(tenant,channel));
        });
    }
    private void release(UUID tenant,Channel channel) {
        if(channel.clients.decrementAndGet()==0&&channels.remove(tenant,channel)) {
            channel.closed=true;channel.subscription.dispose();channel.events.tryEmitComplete();
        }
    }
    private final class Channel {
        private final AtomicInteger clients=new AtomicInteger(1);
        private final Sinks.Many<TelemetryVehicle> events=Sinks.many().multicast().onBackpressureBuffer(2048,false);
        private volatile boolean closed;
        private final Disposable subscription;
        private Channel(UUID tenant) {
            subscription=listener.receive(ChannelTopic.of("t:"+tenant+":ch:updates"))
                    .map(message->decode(message.getMessage()))
                    .subscribe(update->{
                        Sinks.EmitResult result=events.tryEmitNext(update);
                        if(result==Sinks.EmitResult.FAIL_OVERFLOW)meters.counter("map.websocket.redis.subscriber.overflows").increment();
                    },error->{meters.counter("map.websocket.redis.listener.errors").increment();events.tryEmitError(error);});
        }
    }
    private TelemetryVehicle decode(String payload) {
        Map<?,?> values=json.readValue(payload,Map.class);
        return new TelemetryVehicle(UUID.fromString(String.valueOf(values.get("vehicleId"))),Instant.parse(String.valueOf(values.get("ts"))),
                ((Number)values.get("seq")).longValue(),((Number)values.get("lat")).doubleValue(),((Number)values.get("lon")).doubleValue(),
                number(values.get("heading")),number(values.get("speed")),((Number)values.get("battery")).intValue(),String.valueOf(values.get("status")));
    }
    private static Float number(Object value){return value instanceof Number number?number.floatValue():null;}
}
