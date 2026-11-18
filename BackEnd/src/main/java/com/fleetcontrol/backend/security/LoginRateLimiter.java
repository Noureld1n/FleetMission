package com.fleetcontrol.backend.security;

import com.fleetcontrol.backend.config.AppProperties;
import com.fleetcontrol.backend.exception.ApiException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

@Component
public class LoginRateLimiter {
    private static final DefaultRedisScript<Long> INCREMENT_WINDOW = new DefaultRedisScript<>("""
            local attempts = redis.call('INCR', KEYS[1])
            if attempts == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return attempts
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final AppProperties.LoginRateLimit settings;

    public LoginRateLimiter(ReactiveStringRedisTemplate redis, AppProperties properties) {
        this.redis = redis;
        this.settings = properties.auth().loginRateLimit();
    }

    public Mono<Void> check(InetSocketAddress remoteAddress) {
        String address = remoteAddress == null ? "unknown"
                : remoteAddress.getAddress() == null
                    ? remoteAddress.getHostString()
                    : remoteAddress.getAddress().getHostAddress();
        String key = "auth:login:ip:" + sha256Hex(address);
        Duration window = settings.window();

        return redis.execute(INCREMENT_WINDOW, List.of(key), Long.toString(window.toMillis()))
                .next()
                .switchIfEmpty(Mono.error(rateLimiterUnavailable()))
                .flatMap(attempts -> {
                    if (attempts > settings.maxAttempts()) {
                        return Mono.<Void>error(new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                "LOGIN_RATE_LIMITED", "Too many login attempts. Try again later."));
                    }
                    return Mono.<Void>empty();
                })
                .onErrorMap(exception -> exception instanceof ApiException
                        ? exception : rateLimiterUnavailable());
    }

    private static ApiException rateLimiterUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "LOGIN_RATE_LIMIT_UNAVAILABLE", "Authentication is temporarily unavailable.");
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
