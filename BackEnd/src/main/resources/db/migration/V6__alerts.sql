CREATE TABLE public.alerts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    vehicle_id uuid,
    district_id uuid,
    kind text NOT NULL CHECK (kind IN ('low_battery', 'vehicle_offline', 'district_outage')),
    severity text NOT NULL CHECK (severity IN ('info', 'warning', 'critical')),
    status text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'acknowledged', 'resolved')),
    opened_at timestamptz NOT NULL DEFAULT now(),
    acknowledged_at timestamptz,
    resolved_at timestamptz,
    detail jsonb NOT NULL DEFAULT '{}'::jsonb,
    CHECK (vehicle_id IS NOT NULL OR district_id IS NOT NULL),
    CHECK ((status = 'resolved') = (resolved_at IS NOT NULL)),
    CHECK (resolved_at IS NULL OR resolved_at >= opened_at),
    FOREIGN KEY (tenant_id, vehicle_id) REFERENCES public.vehicles (tenant_id, id),
    FOREIGN KEY (tenant_id, district_id) REFERENCES public.districts (tenant_id, id),
    UNIQUE (tenant_id, id)
);
CREATE INDEX alerts_tenant_status_opened_idx ON public.alerts (tenant_id, status, opened_at DESC);
CREATE UNIQUE INDEX alerts_one_open_vehicle_kind_idx
    ON public.alerts (tenant_id, vehicle_id, kind)
    WHERE vehicle_id IS NOT NULL AND status <> 'resolved';

ALTER TABLE public.alerts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.alerts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.alerts
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON public.alerts TO app_runtime;
