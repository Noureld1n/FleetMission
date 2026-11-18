package com.fleetcontrol.backend.security;

import com.fleetcontrol.backend.repository.IdentityRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class ApiKeyAuthenticationManager implements ReactiveAuthenticationManager {
    private final IdentityRepository identityRepository;

    public ApiKeyAuthenticationManager(IdentityRepository identityRepository) {
        this.identityRepository = identityRepository;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        if (!(authentication instanceof ApiKeyAuthenticationToken token)) {
            return Mono.empty();
        }

        ApiKeyCredential credential = ApiKeyCredential.parse((String) token.getCredentials());
        if (credential == null) {
            return invalidKey();
        }

        return identityRepository.findApiKey(credential.prefix())
                .filter(record -> record.revokedAt() == null && credential.matchesHash(record.keyHash()))
                .<Authentication>map(record -> new FleetAuthentication(
                        new TenantIdentity(record.keyId(), record.tenantId(), "ingest"),
                        List.of(new SimpleGrantedAuthority("ROLE_INGEST"))))
                .switchIfEmpty(invalidKey());
    }

    private static <T> Mono<T> invalidKey() {
        return Mono.error(new BadCredentialsException("Invalid API key"));
    }
}
