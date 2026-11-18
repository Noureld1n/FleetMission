package com.fleetcontrol.backend.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public final class FleetAuthentication extends AbstractAuthenticationToken {
    private final TenantIdentity identity;

    public FleetAuthentication(TenantIdentity identity, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.identity = identity;
        super.setAuthenticated(true);
    }

    @Override
    public Object getPrincipal() {
        return identity;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public void setAuthenticated(boolean authenticated) {
        if (authenticated) {
            throw new IllegalArgumentException("Use the authenticated constructor");
        }
        super.setAuthenticated(false);
    }
}
