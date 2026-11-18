-- Run once per database as a PostgreSQL administrator before Flyway.
-- Install the Windows extension binaries (PostGIS/TimescaleDB) before running this file.
-- This file creates cluster roles and database extensions; Flyway migrations cannot safely
-- provision these with the least-privilege app_owner account.

DO $$ BEGIN
    CREATE ROLE app_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    CREATE ROLE app_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    CREATE ROLE app_lookup NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE BYPASSRLS;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Set passwords out of band (for example with psql's interactive \\password command).
-- Never put credentials in this file or commit them.

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE SCHEMA IF NOT EXISTS app AUTHORIZATION app_owner;
GRANT USAGE, CREATE ON SCHEMA app TO app_owner;
GRANT USAGE, CREATE ON SCHEMA app TO app_lookup;
GRANT USAGE ON SCHEMA app TO app_runtime;
GRANT CREATE ON SCHEMA public TO app_owner;
GRANT USAGE ON SCHEMA public TO app_runtime, app_lookup;

-- app_owner may temporarily SET ROLE app_lookup only while creating the two
-- SECURITY DEFINER lookup functions in the corresponding migration.
GRANT app_lookup TO app_owner;
