-- Local-only Fleet Mission Control demo data.
-- Run as a PostgreSQL superuser in the fleetmission database after Flyway has
-- applied every migration. Re-running resets these demo users and sample rows.
-- Password for all five demo accounts: Fmc-Local-7Qv!m3R#p9Zx

BEGIN;

INSERT INTO public.tenants (name, slug)
VALUES ('Fleet Demo North', 'fleet-demo-north'),
       ('Fleet Demo South', 'fleet-demo-south')
ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name;

-- Shared password is BCrypt-hashed at work factor 12 by pgcrypto.
INSERT INTO public.users (tenant_id, email, password_hash, display_name, role)
SELECT t.id, seed.email,
       public.crypt(seed.password, public.gen_salt('bf', 12)),
       seed.display_name, seed.role
FROM (VALUES
    ('fleet-demo-north', 'operator@fleet.local',      'Fmc-Local-7Qv!m3R#p9Zx', 'Noor Hassan',       'operator'),
    ('fleet-demo-north', 'ops.lead@fleet.local',      'Fmc-Local-7Qv!m3R#p9Zx', 'Mariam Adel',       'operator'),
    ('fleet-demo-north', 'viewer@fleet.local',        'Fmc-Local-7Qv!m3R#p9Zx', 'Omar Samir',        'viewer'),
    ('fleet-demo-south', 'south.operator@fleet.local','Fmc-Local-7Qv!m3R#p9Zx', 'Salma Youssef',     'operator'),
    ('fleet-demo-south', 'south.viewer@fleet.local',  'Fmc-Local-7Qv!m3R#p9Zx', 'Karim Nabil',       'viewer')
) AS seed(tenant_slug, email, password, display_name, role)
JOIN public.tenants AS t ON t.slug = seed.tenant_slug
ON CONFLICT (email) DO UPDATE
SET tenant_id = EXCLUDED.tenant_id,
    password_hash = EXCLUDED.password_hash,
    display_name = EXCLUDED.display_name,
    role = EXCLUDED.role,
    disabled_at = NULL;

INSERT INTO public.districts (tenant_id, name, boundary)
SELECT t.id, seed.name, public.ST_GeogFromText(seed.boundary_wkt)
FROM (VALUES
    ('fleet-demo-north', 'Maadi',       'SRID=4326;POLYGON((31.220 29.940,31.300 29.940,31.300 29.990,31.220 29.990,31.220 29.940))'),
    ('fleet-demo-north', 'Downtown',    'SRID=4326;POLYGON((31.205 30.025,31.265 30.025,31.265 30.075,31.205 30.075,31.205 30.025))'),
    ('fleet-demo-north', 'New Cairo',   'SRID=4326;POLYGON((31.425 30.005,31.495 30.005,31.495 30.065,31.425 30.065,31.425 30.005))'),
    ('fleet-demo-south', 'Alexandria Corniche', 'SRID=4326;POLYGON((29.865 31.180,29.930 31.180,29.930 31.235,29.865 31.235,29.865 31.180))'),
    ('fleet-demo-south', 'Stanley',     'SRID=4326;POLYGON((29.935 31.200,30.005 31.200,30.005 31.255,29.935 31.255,29.935 31.200))'),
    ('fleet-demo-south', 'Smouha',      'SRID=4326;POLYGON((29.915 31.180,29.980 31.180,29.980 31.235,29.915 31.235,29.915 31.180))')
) AS seed(tenant_slug, name, boundary_wkt)
JOIN public.tenants AS t ON t.slug = seed.tenant_slug
ON CONFLICT (tenant_id, name) DO UPDATE
SET boundary = EXCLUDED.boundary;

WITH seed(tenant_slug, external_ref, kind, district_name, status, battery_pct,
          base_lat, base_lon, ordinal) AS (
    VALUES
    ('fleet-demo-north', 'FMC-DEMO-N-DR-001', 'drone',     'Maadi',       'active',   78, 29.965, 31.260, 1),
    ('fleet-demo-north', 'FMC-DEMO-N-SC-014', 'scooter',   'Maadi',       'charging', 18, 29.970, 31.270, 2),
    ('fleet-demo-north', 'FMC-DEMO-N-DR-008', 'drone',     'Downtown',    'active',   64, 30.050, 31.235, 3),
    ('fleet-demo-north', 'FMC-DEMO-N-EV-003', 'ev_charger','New Cairo',   'idle',     92, 30.035, 31.460, 4),
    ('fleet-demo-north', 'FMC-DEMO-N-SC-021', 'scooter',   'Downtown',    'fault',    46, 30.045, 31.245, 5),
    ('fleet-demo-north', 'FMC-DEMO-N-DR-011', 'drone',     'New Cairo',   'offline',  31, 30.045, 31.470, 6),
    ('fleet-demo-south', 'FMC-DEMO-S-DR-002', 'drone',     'Alexandria Corniche', 'active',   83, 31.205, 29.900, 7),
    ('fleet-demo-south', 'FMC-DEMO-S-SC-006', 'scooter',   'Stanley',     'charging', 21, 31.225, 29.965, 8),
    ('fleet-demo-south', 'FMC-DEMO-S-EV-001', 'ev_charger','Smouha',      'idle',     96, 31.210, 29.945, 9),
    ('fleet-demo-south', 'FMC-DEMO-S-DR-009', 'drone',     'Stanley',     'offline',  27, 31.230, 29.975, 10)
)
INSERT INTO public.vehicles (tenant_id, external_ref, kind, home_district_id, status)
SELECT t.id, seed.external_ref, seed.kind, d.id, seed.status
FROM seed
JOIN public.tenants AS t ON t.slug = seed.tenant_slug
JOIN public.districts AS d ON d.tenant_id = t.id AND d.name = seed.district_name
ON CONFLICT (tenant_id, external_ref) DO UPDATE
SET kind = EXCLUDED.kind,
    home_district_id = EXCLUDED.home_district_id,
    status = EXCLUDED.status,
    retired_at = NULL;

-- Keep exactly one generated day of samples for the demo vehicles on each run.
DELETE FROM public.telemetry AS telemetry
USING public.vehicles AS vehicle
WHERE telemetry.vehicle_id = vehicle.id
  AND vehicle.external_ref LIKE 'FMC-DEMO-%';

WITH seed(tenant_slug, external_ref, status, battery_pct, base_lat, base_lon, ordinal) AS (
    VALUES
    ('fleet-demo-north', 'FMC-DEMO-N-DR-001', 'active',   78, 29.965, 31.260, 1),
    ('fleet-demo-north', 'FMC-DEMO-N-SC-014', 'charging', 18, 29.970, 31.270, 2),
    ('fleet-demo-north', 'FMC-DEMO-N-DR-008', 'active',   64, 30.050, 31.235, 3),
    ('fleet-demo-north', 'FMC-DEMO-N-EV-003', 'idle',     92, 30.035, 31.460, 4),
    ('fleet-demo-north', 'FMC-DEMO-N-SC-021', 'fault',    46, 30.045, 31.245, 5),
    ('fleet-demo-north', 'FMC-DEMO-N-DR-011', 'offline',  31, 30.045, 31.470, 6),
    ('fleet-demo-south', 'FMC-DEMO-S-DR-002', 'active',   83, 31.205, 29.900, 7),
    ('fleet-demo-south', 'FMC-DEMO-S-SC-006', 'charging', 21, 31.225, 29.965, 8),
    ('fleet-demo-south', 'FMC-DEMO-S-EV-001', 'idle',     96, 31.210, 29.945, 9),
    ('fleet-demo-south', 'FMC-DEMO-S-DR-009', 'offline',  27, 31.230, 29.975, 10)
), demo_vehicles AS (
    SELECT v.id AS vehicle_id, v.tenant_id, seed.status, seed.battery_pct,
           seed.base_lat::double precision AS base_lat,
           seed.base_lon::double precision AS base_lon,
           seed.ordinal
    FROM seed
    JOIN public.tenants AS t ON t.slug = seed.tenant_slug
    JOIN public.vehicles AS v ON v.tenant_id = t.id AND v.external_ref = seed.external_ref
)
INSERT INTO public.telemetry
    (tenant_id, vehicle_id, ts, seq, lat, lon, heading_deg, speed_kmh, battery_pct, status)
SELECT v.tenant_id,
       v.vehicle_id,
       date_trunc('minute', now())
           - (sample.hour_ago + CASE WHEN v.status = 'offline' THEN 4 ELSE 0 END) * INTERVAL '1 hour',
       1000 + (23 - sample.hour_ago),
       v.base_lat + sin(((23 - sample.hour_ago + v.ordinal) * 0.35)::double precision) * 0.003,
       v.base_lon + cos(((23 - sample.hour_ago + v.ordinal) * 0.35)::double precision) * 0.004,
       ((23 - sample.hour_ago) * 17 + v.ordinal * 31) % 360,
       CASE v.status
           WHEN 'active' THEN 22 + ((23 - sample.hour_ago + v.ordinal) % 17)
           WHEN 'fault' THEN 3
           ELSE 0
       END,
       LEAST(100, v.battery_pct + sample.hour_ago * 0.6)::smallint,
       v.status
FROM demo_vehicles AS v
CROSS JOIN generate_series(0, 23) AS sample(hour_ago)
ON CONFLICT (vehicle_id, ts) DO UPDATE
SET seq = EXCLUDED.seq,
    lat = EXCLUDED.lat,
    lon = EXCLUDED.lon,
    heading_deg = EXCLUDED.heading_deg,
    speed_kmh = EXCLUDED.speed_kmh,
    battery_pct = EXCLUDED.battery_pct,
    status = EXCLUDED.status;

WITH seed(alert_key, tenant_slug, vehicle_ref, district_name, kind, severity,
          status, opened_hours, message, battery_pct) AS (
    VALUES
    ('north-low-battery',  'fleet-demo-north', 'FMC-DEMO-N-SC-014', NULL,            'low_battery',    'warning',  'open',         3, 'Scooter battery is below the reserve threshold', 18),
    ('north-offline',      'fleet-demo-north', 'FMC-DEMO-N-DR-011', NULL,            'vehicle_offline','critical', 'open',         5, 'Drone stopped reporting in New Cairo', NULL),
    ('north-fault',        'fleet-demo-north', 'FMC-DEMO-N-SC-021', NULL,            'vehicle_offline','warning',  'acknowledged',  8, 'Scooter has intermittent telemetry during a drivetrain fault', NULL),
    ('north-district',     'fleet-demo-north', NULL,                'Maadi',         'district_outage','warning',  'open',         2, 'Temporary service interruption reported in Maadi', NULL),
    ('south-offline',      'fleet-demo-south', 'FMC-DEMO-S-DR-009', NULL,            'vehicle_offline','critical', 'open',         6, 'Drone stopped reporting near Stanley', NULL),
    ('south-low-battery',  'fleet-demo-south', 'FMC-DEMO-S-SC-006', NULL,            'low_battery',    'warning',  'open',         4, 'Scooter battery is below the reserve threshold', 21),
    ('south-resolved',     'fleet-demo-south', 'FMC-DEMO-S-SC-006', NULL,            'low_battery',    'info',     'resolved',    30, 'Earlier low-battery event was resolved after charging', 12)
)
INSERT INTO public.alerts
    (id, tenant_id, vehicle_id, district_id, kind, severity, status,
     opened_at, acknowledged_at, resolved_at, updated_at, detail)
SELECT md5('fleet-mission-demo-alert:' || seed.alert_key)::uuid,
       t.id,
       v.id,
       d.id,
       seed.kind,
       seed.severity,
       seed.status,
       now() - make_interval(hours => seed.opened_hours),
       CASE WHEN seed.status IN ('acknowledged', 'resolved')
            THEN now() - make_interval(hours => seed.opened_hours) + INTERVAL '20 minutes'
            ELSE NULL END,
       CASE WHEN seed.status = 'resolved'
            THEN now() - make_interval(hours => seed.opened_hours) + INTERVAL '1 hour'
            ELSE NULL END,
       now(),
       jsonb_strip_nulls(jsonb_build_object(
           'message', seed.message,
           'batteryPct', seed.battery_pct,
           'source', 'demo-seed'))
FROM seed
JOIN public.tenants AS t ON t.slug = seed.tenant_slug
LEFT JOIN public.vehicles AS v ON v.tenant_id = t.id AND v.external_ref = seed.vehicle_ref
LEFT JOIN public.districts AS d ON d.tenant_id = t.id AND d.name = seed.district_name
ON CONFLICT (id) DO UPDATE
SET tenant_id = EXCLUDED.tenant_id,
    vehicle_id = EXCLUDED.vehicle_id,
    district_id = EXCLUDED.district_id,
    kind = EXCLUDED.kind,
    severity = EXCLUDED.severity,
    status = EXCLUDED.status,
    opened_at = EXCLUDED.opened_at,
    acknowledged_at = EXCLUDED.acknowledged_at,
    resolved_at = EXCLUDED.resolved_at,
    updated_at = EXCLUDED.updated_at,
    detail = EXCLUDED.detail;

WITH dashboard_seed(tenant_slug, owner_email, name, is_shared) AS (
    VALUES
    ('fleet-demo-north', 'operator@fleet.local',       'North Operator Board', false),
    ('fleet-demo-north', 'ops.lead@fleet.local',       'North Shared Watchfloor', true),
    ('fleet-demo-south', 'south.operator@fleet.local', 'South Fleet Overview', true)
)
INSERT INTO public.dashboards (id, tenant_id, owner_id, name, is_shared)
SELECT md5('fleet-mission-demo-dashboard:' || seed.owner_email || ':' || seed.name)::uuid,
       t.id, u.id, seed.name, seed.is_shared
FROM dashboard_seed AS seed
JOIN public.tenants AS t ON t.slug = seed.tenant_slug
JOIN public.users AS u ON u.tenant_id = t.id AND u.email = seed.owner_email
ON CONFLICT (tenant_id, owner_id, name) DO UPDATE
SET is_shared = EXCLUDED.is_shared,
    updated_at = now();

WITH dashboard_seed(tenant_slug, owner_email, name, is_shared) AS (
    VALUES
    ('fleet-demo-north', 'operator@fleet.local',       'North Operator Board', false),
    ('fleet-demo-north', 'ops.lead@fleet.local',       'North Shared Watchfloor', true),
    ('fleet-demo-south', 'south.operator@fleet.local', 'South Fleet Overview', true)
), widget_seed(type, config, grid_x, grid_y, grid_w, grid_h) AS (
    VALUES
    ('map',         '{}'::jsonb,                  0, 0, 8, 8),
    ('live_chart',  '{}'::jsonb,                  8, 0, 4, 8),
    ('alert_list',  '{"status":"open"}'::jsonb, 0, 8, 8, 5),
    ('status_tiles','{}'::jsonb,                  8, 8, 4, 5)
)
INSERT INTO public.dashboard_widgets
    (id, tenant_id, dashboard_id, type, config, grid_x, grid_y, grid_w, grid_h)
SELECT md5('fleet-mission-demo-widget:' || dashboard_seed.owner_email || ':' || dashboard_seed.name || ':' || widget_seed.type)::uuid,
       d.tenant_id, d.id, widget_seed.type, widget_seed.config,
       widget_seed.grid_x, widget_seed.grid_y, widget_seed.grid_w, widget_seed.grid_h
FROM dashboard_seed
JOIN public.tenants AS t ON t.slug = dashboard_seed.tenant_slug
JOIN public.users AS u ON u.tenant_id = t.id AND u.email = dashboard_seed.owner_email
JOIN public.dashboards AS d ON d.tenant_id = t.id AND d.owner_id = u.id AND d.name = dashboard_seed.name
CROSS JOIN widget_seed
ON CONFLICT (id) DO UPDATE
SET tenant_id = EXCLUDED.tenant_id,
    dashboard_id = EXCLUDED.dashboard_id,
    type = EXCLUDED.type,
    config = EXCLUDED.config,
    grid_x = EXCLUDED.grid_x,
    grid_y = EXCLUDED.grid_y,
    grid_w = EXCLUDED.grid_w,
    grid_h = EXCLUDED.grid_h;

COMMIT;

-- Demo logins (all use the local-only password printed at the top):
-- operator@fleet.local        operator, Fleet Demo North
-- ops.lead@fleet.local        operator, Fleet Demo North
-- viewer@fleet.local          viewer,   Fleet Demo North
-- south.operator@fleet.local  operator, Fleet Demo South
-- south.viewer@fleet.local    viewer,   Fleet Demo South
