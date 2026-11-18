CREATE TABLE public.dashboards (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    owner_id uuid NOT NULL,
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    is_shared boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, owner_id, name),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, owner_id) REFERENCES public.users (tenant_id, id)
);

CREATE TABLE public.dashboard_widgets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    dashboard_id uuid NOT NULL,
    type text NOT NULL CHECK (type IN ('map', 'live_chart', 'status_tiles', 'alert_list')),
    config jsonb NOT NULL DEFAULT '{}'::jsonb,
    grid_x integer NOT NULL CHECK (grid_x >= 0),
    grid_y integer NOT NULL CHECK (grid_y >= 0),
    grid_w integer NOT NULL CHECK (grid_w >= 1),
    grid_h integer NOT NULL CHECK (grid_h >= 1),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, dashboard_id) REFERENCES public.dashboards (tenant_id, id) ON DELETE CASCADE
);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['dashboards','dashboard_widgets'] LOOP
    EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
  END LOOP;
END $$;

GRANT SELECT, INSERT, UPDATE, DELETE ON public.dashboards, public.dashboard_widgets TO app_runtime;
