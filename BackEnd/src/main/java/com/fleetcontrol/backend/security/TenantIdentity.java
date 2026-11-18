package com.fleetcontrol.backend.security;

import java.util.UUID;

/** Verified identity placed in Reactor Context for tenant-owned work. */
public record TenantIdentity(UUID subjectId, UUID tenantId, String role) {}
