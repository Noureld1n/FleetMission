# Database setup

## New local database

1. Create an empty PostgreSQL database named `fleetmission`.
2. As a PostgreSQL administrator, run `bootstrap.sql` while connected to that database. It installs the required extensions and creates the database roles/schema. The PostgreSQL server must already have compatible TimescaleDB and PostGIS binaries installed.
3. Set passwords for `app_owner` and `app_runtime` out of band (for example, interactively with `psql`'s `\password` command). Set the matching `SPRING_FLYWAY_PASSWORD` and `SPRING_R2DBC_PASSWORD` environment variables.
4. Point the backend at this clean database and start it. Flyway uses `app_owner` and applies `BackEnd/src/main/resources/db/migration` in version order. The service connects as `app_runtime`.

Do not apply these migrations to a database where `V1__initial_schema.sql` was already run. Flyway migrations create the same tables and are intended for a new, empty application database. Keep the old script as a reference; do not run it alongside Flyway.

The migrations seed two demo tenants, but intentionally do not contain reusable user passwords or API-key credentials. For local UI testing, start the backend once so Flyway applies all migrations, then connect to `fleetmission` as a PostgreSQL superuser and run `demo_seed.sql`. It adds five local login accounts, districts, vehicles, 24 hours of telemetry, alerts, and sample dashboards/widgets. All five accounts use the local-only password shown in that script. Re-running it resets the seeded demo account credentials and telemetry samples. Do not use these credentials outside a local development database. For immediate chart history, run `refresh_demo_aggregates.sql` separately with autocommit enabled; TimescaleDB's scheduled aggregate refresh also runs shortly after seeding. The SQL seed fills REST-backed pages and chart history; the live map still needs telemetry sent through the ingest API while the backend and Redis are running. The map also needs `VITE_MAP_STYLE_URL` configured.

The backend accepts machine keys in the `X-API-Key` header using `prefix.secret`. Prefixes use 4–64 ASCII letters, digits, `_` or `-` and are the lookup value stored in `api_keys.key_prefix`; `api_keys.key_hash` stores lowercase SHA-256 hex of the high-entropy secret portion only. Generate secrets from at least 32 random bytes and display each secret only once when a future key-creation flow is added. User password hashes use BCrypt (work factor 12).

## Starting schema choices

The first migration set keeps the draft's concrete demo values: vehicle kinds `drone`, `scooter`, and `ev_charger`; alert kinds `low_battery`, `vehicle_offline`, and `district_outage`; one-day telemetry chunks; 14-day raw retention; and 365-day aggregate retention. The retention and chunk values are starting values and should be revisited with measured workload data. Vehicle alert deduplication is enforced; district-wide deduplication remains open until outage behavior is implemented.

## Continuous aggregate / RLS constraint

TimescaleDB's current continuous-aggregate creation guidance says the source hypertable must not have RLS policies enabled when a continuous aggregate is created. Accordingly, V5 creates aggregates first and enables/forces RLS on `telemetry` afterward. The model also requires scheduled aggregate refreshes to remain correct under this arrangement. Confirm scheduled refresh behavior against the exact TimescaleDB/PostgreSQL image before relying on aggregates in production; do not weaken tenant isolation to work around a refresh issue.
