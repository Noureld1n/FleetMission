package com.fleetcontrol.backend.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

public final class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {
    private final String presentedKey;

    private ApiKeyAuthenticationToken(String presentedKey) {
        super(AuthorityUtils.NO_AUTHORITIES);
        this.presentedKey = presentedKey;
        super.setAuthenticated(false);
    }

    public static ApiKeyAuthenticationToken unauthenticated(String presentedKey) {
        return new ApiKeyAuthenticationToken(presentedKey);
    }

    @Override
    public Object getPrincipal() {
        return "api-key";
    }

    @Override
    public Object getCredentials() {
        return presentedKey;
    }

    @Override
    public void setAuthenticated(boolean authenticated) {
        if (authenticated) {
            throw new IllegalArgumentException("API keys must be validated by the authentication manager");
        }
        super.setAuthenticated(false);
    }

    @Override
    public String toString() {
        return "ApiKeyAuthenticationToken[credentials=REDACTED]";
    }
}
