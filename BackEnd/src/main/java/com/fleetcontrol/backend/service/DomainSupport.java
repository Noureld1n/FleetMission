package com.fleetcontrol.backend.service;

import com.fleetcontrol.backend.exception.ApiException;
import com.fleetcontrol.backend.security.TenantIdentity;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

import java.util.UUID;

final class DomainSupport {
    private DomainSupport() {}
    static Mono<TenantIdentity> identity() {
        return Mono.deferContextual(ctx -> ctx.<TenantIdentity>getOrEmpty(TenantIdentity.class)
                .map(Mono::just).orElseGet(() -> Mono.error(new ApiException(HttpStatus.UNAUTHORIZED,
                        "TENANT_CONTEXT_REQUIRED", "Authenticated tenant context is required."))));
    }
    static <E extends Enum<E>> String enumValue(Class<E> type, String value, String name) {
        if (value == null) return null;
        try { return Enum.valueOf(type, value.trim().replace('-', '_').toUpperCase()).name().toLowerCase(); }
        catch (IllegalArgumentException ex) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_" + name,
                "The supplied " + name.toLowerCase().replace('_',' ') + " is not supported."); }
    }
    static UUID cursor(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException ex) { throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_CURSOR","Cursor must be a UUID."); }
    }
    static int limit(int limit) {
        if (limit < 1 || limit > 100) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_LIMIT","Limit must be between 1 and 100.");
        return limit;
    }
}
