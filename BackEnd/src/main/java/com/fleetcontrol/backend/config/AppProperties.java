package com.fleetcontrol.backend.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String instanceId,
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Cors cors,
        @Valid @NotNull Auth auth,
        @Valid @NotNull Ingestion ingestion,
        @Valid @NotNull Map map,
        @Valid @NotNull Alerts alerts
) {
    public record Jwt(
            String secret,
            @NotBlank String issuer,
            @NotNull Duration ttl
    ) {
        public Jwt {
            requirePositive(ttl, "app.jwt.ttl");
        }
    }

    public record Cors(@NotEmpty List<@NotBlank String> allowedOrigins) {}

    public record Auth(@Valid @NotNull LoginRateLimit loginRateLimit) {}

    public record LoginRateLimit(
            @Min(1) int maxAttempts,
            @NotNull Duration window
    ) {
        public LoginRateLimit {
            requirePositive(window, "app.auth.login-rate-limit.window");
        }
    }

    public record Ingestion(
            @Min(1) int queueCapacity,
            @Min(1) int batchSize,
            @NotNull Duration maxWait
    ) {
        public Ingestion {
            requirePositive(maxWait, "app.ingestion.max-wait");
        }
    }

    public record Map(
            @Min(0) @Max(22) int tileZoom,
            @NotNull Duration flushInterval,
            @NotNull Duration authTimeout
    ) {
        public Map {
            requirePositive(flushInterval, "app.map.flush-interval");
            requirePositive(authTimeout, "app.map.auth-timeout");
        }
    }

    public record Alerts(
            @Min(0) @jakarta.validation.constraints.Max(100) int batteryThreshold,
            @NotNull Duration offlineAfter
    ) {
        public Alerts {
            requirePositive(offlineAfter, "app.alerts.offline-after");
        }
    }

    private static void requirePositive(Duration value, String propertyName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be a positive duration");
        }
    }
}
