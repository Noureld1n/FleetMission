package com.fleetcontrol.backend.security;

import com.fleetcontrol.backend.config.AppProperties;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Validates the JWT extracted from the first {@code {"type":"auth","token":"..."}} frame. */
@Component
public class WebSocketJwtAuthenticator {
    private final ReactiveJwtDecoder jwtDecoder;
    private final AppProperties.Map mapProperties;

    public WebSocketJwtAuthenticator(ReactiveJwtDecoder jwtDecoder, AppProperties properties) {
        this.jwtDecoder = jwtDecoder;
        this.mapProperties = properties.map();
    }

    public Mono<TenantIdentity> authenticateFirstAuthMessage(Mono<String> tokenFromFirstFrame) {
        return tokenFromFirstFrame
                .timeout(mapProperties.authTimeout())
                .switchIfEmpty(Mono.error(new BadCredentialsException("WebSocket authentication failed")))
                .flatMap(jwtDecoder::decode)
                .map(WebSocketJwtAuthenticator::identityFrom)
                .onErrorMap(exception -> new BadCredentialsException("WebSocket authentication failed"));
    }

    private static TenantIdentity identityFrom(Jwt jwt) {
        return new TenantIdentity(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString("tenant_id")),
                jwt.getClaimAsString("role"));
    }
}
