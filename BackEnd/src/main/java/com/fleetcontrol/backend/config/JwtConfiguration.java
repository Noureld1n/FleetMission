package com.fleetcontrol.backend.config;

import com.fleetcontrol.backend.security.FleetAuthentication;
import com.fleetcontrol.backend.security.TenantIdentity;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.core.convert.converter.Converter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Configuration
public class JwtConfiguration {
    private static final Set<String> ALLOWED_ROLES = Set.of("operator", "viewer");

    @Bean
    SecretKey jwtSecretKey(AppProperties properties) {
        byte[] secret = properties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("APP_JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(jwtSecretKey));
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(SecretKey jwtSecretKey, AppProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            try {
                UUID.fromString(jwt.getSubject());
                UUID.fromString(jwt.getClaimAsString("tenant_id"));
                if (ALLOWED_ROLES.contains(jwt.getClaimAsString("role"))) {
                    return OAuth2TokenValidatorResult.success();
                }
            } catch (RuntimeException ignored) {
                // Return the same generic token failure for every malformed required claim.
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token", "Required token claims are invalid.", null));
        };

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()), requiredClaims));
        return decoder;
    }

    @Bean
    Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        return jwt -> {
            String role = jwt.getClaimAsString("role");
            TenantIdentity identity = new TenantIdentity(
                    UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString("tenant_id")),
                    role);
            return Mono.just(new FleetAuthentication(identity,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(java.util.Locale.ROOT)))));
        };
    }
}
