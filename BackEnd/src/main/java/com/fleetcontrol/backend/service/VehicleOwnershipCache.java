package com.fleetcontrol.backend.service;

import com.fleetcontrol.backend.repository.TelemetryRepository;
import com.fleetcontrol.backend.security.TenantIdentity;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Tenant-keyed membership cache with a 30-second refresh TTL. */
@Component
public class VehicleOwnershipCache {
    private static final Duration TTL=Duration.ofSeconds(30);
    private final TelemetryRepository repository;
    private final TenantTransaction transactions;
    private final Map<UUID,Entry> entries=new ConcurrentHashMap<>();
    public VehicleOwnershipCache(TelemetryRepository repository,TenantTransaction transactions) { this.repository=repository; this.transactions=transactions; }
    public Mono<Set<UUID>> activeVehicles(TenantIdentity identity) {
        Entry cached=entries.get(identity.tenantId());
        if(cached!=null && cached.expiresAt().isAfter(Instant.now())) return Mono.just(cached.vehicleIds());
        return transactions.inTenantTransaction(()->repository.activeVehicleIds(identity.tenantId()))
                .doOnNext(ids->entries.put(identity.tenantId(),new Entry(Set.copyOf(ids),Instant.now().plus(TTL))));
    }
    public void invalidate(UUID tenantId) { entries.remove(tenantId); }
    private record Entry(Set<UUID> vehicleIds,Instant expiresAt) {}
}
