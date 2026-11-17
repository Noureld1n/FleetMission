-- Fleet Mission Control initial schema
-- Derived from "Fleet Mission Control Data Model.md".
-- Run as the database/migration owner. The runtime role must never own these tables.

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS timescaledb;

DO $$ BEGIN
    CREATE ROLE app_owner NOLOGIN;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    CREATE ROLE app_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    CREATE ROLE app_lookup NOLOGIN BYPASSRLS;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE SCHEMA IF NOT EXISTS app AUTHORIZATION app_owner;
GRANT USAGE ON SCHEMA public, app TO app_runtime, app_lookup;

CREATE TABLE public.tenants (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    slug text NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    email citext NOT NULL UNIQUE,
    password_hash text NOT NULL,
    display_name text NOT NULL,
    role text NOT NULL CHECK (role IN ('operator', 'viewer')),
    created_at timestamptz NOT NULL DEFAULT now(),
    disabled_at timestamptz,
    UNIQUE (tenant_id, id)
);

CREATE TABLE public.api_keys (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    name text NOT NULL,
    key_prefix text NOT NULL UNIQUE,
    key_hash text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    revoked_at timestamptz CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    UNIQUE (tenant_id, name),
    UNIQUE (tenant_id, id)
);

CREATE TABLE public.districts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    name text NOT NULL,
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

CREATE TABLE public.telemetry (
    tenant_id uuid NOT NULL,
    vehicle_id uuid NOT NULL,
    ts timestamptz NOT NULL,
    seq bigint NOT NULL CHECK (seq >= 0),
    lat double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lon double precision NOT NULL CHECK (lon BETWEEN -180 AND 180),
    heading_deg real CHECK (heading_deg >= 0 AND heading_deg < 360),
    speed_kmh real CHECK (speed_kmh >= 0),
    battery_pct smallint NOT NULL CHECK (battery_pct BETWEEN 0 AND 100),
    status text NOT NULL CHECK (status IN ('idle', 'active', 'charging', 'offline', 'fault')),
    PRIMARY KEY (vehicle_id, ts),
    FOREIGN KEY (tenant_id, vehicle_id) REFERENCES public.vehicles (tenant_id, id)
);
SELECT create_hypertable('public.telemetry', 'ts', if_not_exists => TRUE, chunk_time_interval => INTERVAL '1 day');
CREATE INDEX telemetry_tenant_ts_idx ON public.telemetry (tenant_id, ts DESC);
SELECT add_retention_policy('public.telemetry', INTERVAL '14 days', if_not_exists => TRUE);

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

-- Continuous aggregates provide tenant-scoped chart reads without scanning raw telemetry.
CREATE MATERIALIZED VIEW public.telemetry_1m
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT tenant_id, vehicle_id, time_bucket(INTERVAL '1 minute', ts) AS bucket,
       avg(battery_pct)::double precision AS battery_avg,
       min(battery_pct) AS battery_min,
       avg(speed_kmh)::double precision AS speed_avg,
       max(speed_kmh) AS speed_max,
       count(*) AS sample_count
FROM public.telemetry
GROUP BY tenant_id, vehicle_id, time_bucket(INTERVAL '1 minute', ts)
WITH NO DATA;

CREATE MATERIALIZED VIEW public.telemetry_fleet_1m
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT tenant_id, time_bucket(INTERVAL '1 minute', ts) AS bucket,
       avg(battery_pct)::double precision AS battery_avg,
       min(battery_pct) AS battery_min,
       count(*) AS sample_count
FROM public.telemetry
GROUP BY tenant_id, time_bucket(INTERVAL '1 minute', ts)
WITH NO DATA;

SELECT add_continuous_aggregate_policy('public.telemetry_1m',
    start_offset => INTERVAL '15 days', end_offset => INTERVAL '1 minute', schedule_interval => INTERVAL '1 minute', if_not_exists => TRUE);
SELECT add_continuous_aggregate_policy('public.telemetry_fleet_1m',
    start_offset => INTERVAL '15 days', end_offset => INTERVAL '1 minute', schedule_interval => INTERVAL '1 minute', if_not_exists => TRUE);
SELECT add_retention_policy('public.telemetry_1m', INTERVAL '365 days', if_not_exists => TRUE);
SELECT add_retention_policy('public.telemetry_fleet_1m', INTERVAL '365 days', if_not_exists => TRUE);

-- Narrow pre-authentication lookups. The owner can bypass RLS, but callers only get these functions.
GRANT SELECT (id, tenant_id, email, password_hash, role, disabled_at) ON public.users TO app_lookup;
GRANT SELECT (id, tenant_id, key_prefix, key_hash, revoked_at) ON public.api_keys TO app_lookup;
CREATE OR REPLACE FUNCTION app.login_lookup(p_email citext)
RETURNS TABLE (user_id uuid, tenant_id uuid, password_hash text, role text, disabled_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$ SELECT u.id, u.tenant_id, u.password_hash, u.role, u.disabled_at
      FROM public.users AS u WHERE u.email = p_email $$;
CREATE OR REPLACE FUNCTION app.api_key_lookup(p_prefix text)
RETURNS TABLE (key_id uuid, tenant_id uuid, key_hash text, revoked_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$ SELECT k.id, k.tenant_id, k.key_hash, k.revoked_at
      FROM public.api_keys AS k WHERE k.key_prefix = p_prefix $$;
ALTER FUNCTION app.login_lookup(citext) OWNER TO app_lookup;
ALTER FUNCTION app.api_key_lookup(text) OWNER TO app_lookup;
REVOKE ALL ON FUNCTION app.login_lookup(citext) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.api_key_lookup(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.login_lookup(citext), app.api_key_lookup(text) TO app_runtime;

-- Aggregate views are owned by the migration/table owner; app_runtime receives no direct
-- privileges on the aggregates and can only read through the tenant-filtered views.
CREATE VIEW app.telemetry_1m WITH (security_barrier = true) AS
SELECT tenant_id, vehicle_id, bucket, battery_avg, battery_min, speed_avg, speed_max, sample_count
FROM public.telemetry_1m
WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid;
CREATE VIEW app.telemetry_fleet_1m WITH (security_barrier = true) AS
SELECT tenant_id, bucket, battery_avg, battery_min, sample_count
FROM public.telemetry_fleet_1m
WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid;

-- Uniform fail-closed tenant policies.
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['users','api_keys','districts','vehicles','telemetry','alerts','dashboards','dashboard_widgets'] LOOP
    EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
  END LOOP;
END $$;

-- Runtime grants are intentionally explicit. Migrations/ownership use app_owner separately.
GRANT SELECT, INSERT, UPDATE, DELETE ON public.users, public.api_keys, public.districts,
    public.vehicles, public.telemetry, public.alerts, public.dashboards, public.dashboard_widgets TO app_runtime;
GRANT SELECT ON app.telemetry_1m, app.telemetry_fleet_1m TO app_runtime;
REVOKE ALL ON public.telemetry_1m, public.telemetry_fleet_1m FROM app_runtime;

