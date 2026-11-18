package com.fleetcontrol.backend.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Optional;

public class TenantContextWebFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Optional::ofNullable)
                .defaultIfEmpty(Optional.empty())
                .flatMap(authentication -> continueWithIdentity(exchange, chain, authentication));
    }

    private Mono<Void> continueWithIdentity(
            ServerWebExchange exchange,
            WebFilterChain chain,
            Optional<Authentication> authentication) {
        if (authentication.isPresent()
                && authentication.get().isAuthenticated()
                && authentication.get().getPrincipal() instanceof TenantIdentity identity) {
            return chain.filter(exchange)
                    .contextWrite(context -> context.put(TenantIdentity.class, identity));
        }
        return chain.filter(exchange);
    }
}
