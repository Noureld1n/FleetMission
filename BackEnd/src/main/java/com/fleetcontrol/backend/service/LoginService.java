package com.fleetcontrol.backend.service;

import com.fleetcontrol.backend.config.AppProperties;
import com.fleetcontrol.backend.dto.request.LoginRequest;
import com.fleetcontrol.backend.dto.response.LoginResponse;
import com.fleetcontrol.backend.exception.ApiException;
import com.fleetcontrol.backend.repository.IdentityRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
public class LoginService {
    private static final Set<String> USER_ROLES = Set.of("operator", "viewer");

    private final IdentityRepository identityRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final AppProperties.Jwt jwtProperties;
    private final Clock clock;
    private final String dummyPasswordHash;

    public LoginService(
            IdentityRepository identityRepository,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            AppProperties properties,
            Clock clock) {
        this.identityRepository = identityRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = properties.jwt();
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public Mono<LoginResponse> login(LoginRequest request) {
        return identityRepository.findLoginAccount(request.email())
                .flatMap(account -> verifyPassword(request.password(), account.passwordHash())
                        .flatMap(matches -> {
                            if (!matches || account.disabledAt() != null
                                    || !USER_ROLES.contains(account.role())) {
                                return invalidCredentials();
                            }
                            return Mono.just(issueToken(account));
                        }))
                .switchIfEmpty(verifyPassword(request.password(), dummyPasswordHash)
                        .then(Mono.error(invalidCredentialsException())));
    }

    private Mono<Boolean> verifyPassword(String rawPassword, String encodedPassword) {
        return Mono.fromCallable(() -> passwordEncoder.matches(rawPassword, encodedPassword))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorReturn(false);
    }

    private LoginResponse issueToken(IdentityRepository.LoginAccount account) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(jwtProperties.ttl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(account.userId().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("tenant_id", account.tenantId().toString())
                .claim("role", account.role())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new LoginResponse(token, "Bearer", jwtProperties.ttl().toSeconds());
    }

    private static <T> Mono<T> invalidCredentials() {
        return Mono.error(invalidCredentialsException());
    }

    private static ApiException invalidCredentialsException() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "Email or password is incorrect.");
    }
}
