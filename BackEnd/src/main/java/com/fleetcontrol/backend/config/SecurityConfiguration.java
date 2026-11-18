package com.fleetcontrol.backend.config;

import com.fleetcontrol.backend.security.ApiKeyAuthenticationManager;
import com.fleetcontrol.backend.security.ApiKeyAuthenticationToken;
import com.fleetcontrol.backend.security.ProblemResponseWriter;
import com.fleetcontrol.backend.security.TenantContextWebFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfiguration {
    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.cors().allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "X-API-Key", "Last-Event-ID"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    private AuthenticationWebFilter newApiKeyAuthenticationWebFilter(
            ApiKeyAuthenticationManager authenticationManager,
            ProblemResponseWriter problemResponseWriter) {
        AuthenticationWebFilter filter = new AuthenticationWebFilter(authenticationManager);
        filter.setServerAuthenticationConverter(exchange -> {
            String value = exchange.getRequest().getHeaders().getFirst("X-API-Key");
            return value == null || value.isBlank()
                    ? Mono.empty()
                    : Mono.just(ApiKeyAuthenticationToken.unauthenticated(value));
        });
        filter.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        filter.setAuthenticationFailureHandler(new ServerAuthenticationEntryPointFailureHandler(
                (exchange, exception) -> problemResponseWriter.write(exchange, HttpStatus.UNAUTHORIZED,
                        "INVALID_API_KEY", "API key is missing or invalid.")));
        return filter;
    }

    @Bean
    @Order(1)
    SecurityWebFilterChain ingestionSecurityChain(
            ServerHttpSecurity http,
            ApiKeyAuthenticationManager authenticationManager,
            CorsConfigurationSource corsConfigurationSource,
            ProblemResponseWriter problemResponseWriter) {
        AuthenticationWebFilter apiKeyAuthenticationWebFilter =
                newApiKeyAuthenticationWebFilter(authenticationManager, problemResponseWriter);
        TenantContextWebFilter tenantContextWebFilter = new TenantContextWebFilter();
        return http
                .securityMatcher(new PathPatternParserServerWebExchangeMatcher("/ingest/v1/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((exchange, exception) -> problemResponseWriter.write(
                                exchange, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                                "API key is missing or invalid."))
                        .accessDeniedHandler((exchange, exception) -> problemResponseWriter.write(
                                exchange, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                                "You are not allowed to perform this action.")))
                .authorizeExchange(exchanges -> exchanges.anyExchange().hasAuthority("ROLE_INGEST"))
                .addFilterAt(apiKeyAuthenticationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .addFilterAfter(tenantContextWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    @Order(2)
    SecurityWebFilterChain userSecurityChain(
            ServerHttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            ProblemResponseWriter problemResponseWriter,
            Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter) {
        TenantContextWebFilter tenantContextWebFilter = new TenantContextWebFilter();
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((exchange, exception) -> problemResponseWriter.write(
                                exchange, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                                "Authentication is required or invalid."))
                        .accessDeniedHandler((exchange, exception) -> problemResponseWriter.write(
                                exchange, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                                "You are not allowed to perform this action.")))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .pathMatchers("/ws/map").permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .pathMatchers("/actuator/**").denyAll()
                        .pathMatchers(HttpMethod.GET, "/api/**").hasAnyRole("VIEWER", "OPERATOR")
                        .pathMatchers(HttpMethod.HEAD, "/api/**").hasAnyRole("VIEWER", "OPERATOR")
                        .pathMatchers("/api/**").hasRole("OPERATOR")
                        .anyExchange().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .addFilterAfter(tenantContextWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
