-- Extensions and cluster roles are provisioned by DataBase/bootstrap.sql as an administrator.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pgcrypto')
       OR NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'citext')
       OR NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'postgis')
       OR NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        RAISE EXCEPTION 'Required extensions are missing; run DataBase/bootstrap.sql first';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_owner')
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_runtime')
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_lookup') THEN
        RAISE EXCEPTION 'Required roles are missing; run DataBase/bootstrap.sql first';
    END IF;
END $$;

GRANT USAGE ON SCHEMA app TO app_runtime;
GRANT USAGE ON SCHEMA public TO app_runtime;
