CREATE TABLE public.scenario_commands (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    district_id uuid NOT NULL,
    command_type text NOT NULL CHECK (command_type IN ('outage')),
    payload jsonb NOT NULL,
    state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','acknowledged','expired')),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    lease_until timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    acknowledged_at timestamptz,
    FOREIGN KEY (tenant_id, district_id) REFERENCES public.districts(tenant_id, id),
    CHECK (expires_at > created_at),
    CHECK (acknowledged_at IS NULL OR acknowledged_at >= created_at),
    CHECK ((state = 'acknowledged') = (acknowledged_at IS NOT NULL)),
    UNIQUE (tenant_id, id)
);

CREATE INDEX scenario_commands_pending_idx
    ON public.scenario_commands (tenant_id, created_at, expires_at)
    WHERE state = 'pending';
ALTER TABLE public.scenario_commands ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.scenario_commands FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.scenario_commands
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON public.scenario_commands TO app_runtime;

CREATE UNIQUE INDEX alerts_one_open_district_kind_idx
    ON public.alerts (tenant_id, district_id, kind)
    WHERE district_id IS NOT NULL AND vehicle_id IS NULL AND status <> 'resolved';
