-- Optional immediate refresh after demo_seed.sql.
-- Run these CALL statements in autocommit mode, outside BEGIN/COMMIT.
-- The normal TimescaleDB refresh policy also updates these aggregates shortly.

CALL refresh_continuous_aggregate(
    'public.telemetry_1m',
    now() - INTERVAL '2 days',
    now()
);

CALL refresh_continuous_aggregate(
    'public.telemetry_fleet_1m',
    now() - INTERVAL '2 days',
    now()
);
