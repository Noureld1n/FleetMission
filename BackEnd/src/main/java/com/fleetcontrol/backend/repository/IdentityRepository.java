package com.fleetcontrol.backend.repository;

import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public class IdentityRepository {
    private final DatabaseClient databaseClient;

    public IdentityRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<LoginAccount> findLoginAccount(String email) {
        return databaseClient.sql("SELECT * FROM app.login_lookup(CAST(:email AS citext))")
                .bind("email", email)
                .map((row, metadata) -> new LoginAccount(
                        row.get("user_id", UUID.class),
                        row.get("tenant_id", UUID.class),
                        row.get("password_hash", String.class),
                        row.get("role", String.class),
                        row.get("disabled_at", OffsetDateTime.class)))
                .one();
    }

    public Mono<ApiKeyRecord> findApiKey(String prefix) {
        return databaseClient.sql("SELECT * FROM app.api_key_lookup(:prefix)")
                .bind("prefix", prefix)
                .map((row, metadata) -> mapApiKey(row))
                .one();
    }

    private static ApiKeyRecord mapApiKey(Row row) {
        return new ApiKeyRecord(
                row.get("key_id", UUID.class),
                row.get("tenant_id", UUID.class),
                row.get("key_hash", String.class),
                row.get("revoked_at", OffsetDateTime.class));
    }

    public record LoginAccount(
            UUID userId,
            UUID tenantId,
            String passwordHash,
            String role,
            OffsetDateTime disabledAt
    ) {}

    public record ApiKeyRecord(UUID keyId, UUID tenantId, String keyHash, OffsetDateTime revokedAt) {}
}
