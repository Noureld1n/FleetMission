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
SELECT create_hypertable('public.telemetry', 'ts', if_not_exists => TRUE,
    chunk_time_interval => INTERVAL '1 day');
CREATE INDEX telemetry_tenant_ts_idx ON public.telemetry (tenant_id, ts DESC);
SELECT add_retention_policy('public.telemetry', INTERVAL '14 days', if_not_exists => TRUE);

-- RLS is applied after continuous aggregate creation in V5 because TimescaleDB
-- requires source hypertables to have no RLS policies when a CAGG is created.
GRANT SELECT, INSERT, UPDATE, DELETE ON public.telemetry TO app_runtime;
