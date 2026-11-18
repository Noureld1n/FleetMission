package com.fleetcontrol.backend.service;

import com.fleetcontrol.backend.exception.ApiException;
import com.fleetcontrol.backend.security.TenantIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Supplier;

/** Runs tenant-owned database work in a transaction with its RLS setting as the first SQL statement. */
@Component
public class TenantTransaction {
    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactionalOperator;

    public TenantTransaction(DatabaseClient databaseClient, ReactiveTransactionManager transactionManager) {
        this.databaseClient = databaseClient;
        this.transactionalOperator = TransactionalOperator.create(transactionManager);
    }

    public <T> Mono<T> inTenantTransaction(Supplier<Mono<T>> work) {
        return Mono.deferContextual(context -> {
            TenantIdentity identity = context.<TenantIdentity>getOrEmpty(TenantIdentity.class).orElse(null);
            if (identity == null) {
                return Mono.error(new ApiException(HttpStatus.UNAUTHORIZED,
                        "TENANT_CONTEXT_REQUIRED", "Authenticated tenant context is required."));
            }

            Mono<T> transaction = setTenant(identity.tenantId())
                    .then(Mono.defer(work));
            return transactionalOperator.transactional(transaction);
        });
    }

    private Mono<Void> setTenant(UUID tenantId) {
        return databaseClient.sql("SELECT set_config('app.tenant_id', :tenantId, true)")
                .bind("tenantId", tenantId.toString())
                .map((row, metadata) -> row.get(0, String.class))
                .one()
                .then();
    }
}
