CREATE TABLE public.districts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    boundary geography(Polygon, 4326) NOT NULL CHECK (ST_IsValid(boundary::geometry)),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name),
    UNIQUE (tenant_id, id)
);
CREATE INDEX districts_boundary_gix ON public.districts USING gist (boundary);

CREATE TABLE public.vehicles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    external_ref text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('drone', 'scooter', 'ev_charger')),
    home_district_id uuid,
    status text NOT NULL CHECK (status IN ('idle', 'active', 'charging', 'offline', 'fault')),
    created_at timestamptz NOT NULL DEFAULT now(),
    retired_at timestamptz,
    UNIQUE (tenant_id, external_ref),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, home_district_id) REFERENCES public.districts (tenant_id, id)
);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['districts','vehicles'] LOOP
    EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
  END LOOP;
END $$;

GRANT SELECT, INSERT, UPDATE, DELETE ON public.districts, public.vehicles TO app_runtime;
