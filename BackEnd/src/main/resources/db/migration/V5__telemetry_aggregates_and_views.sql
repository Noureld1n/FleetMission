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
    start_offset => INTERVAL '15 days', end_offset => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute', if_not_exists => TRUE);
SELECT add_continuous_aggregate_policy('public.telemetry_fleet_1m',
    start_offset => INTERVAL '15 days', end_offset => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute', if_not_exists => TRUE);
SELECT add_retention_policy('public.telemetry_1m', INTERVAL '365 days', if_not_exists => TRUE);
SELECT add_retention_policy('public.telemetry_fleet_1m', INTERVAL '365 days', if_not_exists => TRUE);

CREATE VIEW app.telemetry_1m WITH (security_barrier = true) AS
SELECT tenant_id, vehicle_id, bucket, battery_avg, battery_min, speed_avg, speed_max, sample_count
FROM public.telemetry_1m
WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid;
CREATE VIEW app.telemetry_fleet_1m WITH (security_barrier = true) AS
SELECT tenant_id, bucket, battery_avg, battery_min, sample_count
FROM public.telemetry_fleet_1m
WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid;

REVOKE ALL ON public.telemetry_1m, public.telemetry_fleet_1m FROM PUBLIC, app_runtime;
GRANT SELECT ON app.telemetry_1m, app.telemetry_fleet_1m TO app_runtime;

ALTER TABLE public.telemetry ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.telemetry FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.telemetry
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
