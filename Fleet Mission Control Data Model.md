# Fleet Mission Control: Data Model

Nov 17, 2026 · @Noureldin

## Principles and conventions

The model puts tenant isolation and data integrity in the database itself, so a bug in application code cannot leak or corrupt another tenant's data. Nine conventions apply to every table.

| Topic | Convention |
| --- | --- |
| IDs | `uuid` primary keys, defaulting to `gen_random_uuid()` |
| Time | `timestamptz` everywhere, stored in UTC; event time is `ts`, row creation is `created_at` |
| Tenant column | Every tenant-owned table has `tenant_id uuid NOT NULL` referencing `tenants(id)` |
| Composite keys | Every tenant-owned parent table has `UNIQUE (tenant_id, id)`; children reference `(tenant_id, parent_id)`, so a row can never point at another tenant's parent |
| Enumerations | `text` columns with `CHECK` constraints instead of Postgres enum types, so the allowed values change with a plain migration |
| Nullability | `NOT NULL` by default; every nullable column is listed with its reason |
| Naming | snake\_case, plural table names, `_id` suffix for references, `_at` suffix for timestamps |
| Spatial values | Positions are plain `lat`/`lon` doubles; geometry stays inside SQL because R2DBC does not map geometry types |
| Extensions | `timescaledb`, `postgis`, `citext` |

In the table specs below, the constraints column uses PK (primary key), FK (foreign key), UQ (unique), CK (check), and NN (not null).

## Schema at a glance

Nine tables and two continuous aggregates fall into three groups: identity, dashboards, and fleet data, with telemetry as the only hypertable.

&#91;embedded content: schema · 9 tables and 2 continuous aggregates\]

Arrows run from parent to child. An alert points to a vehicle, a district, or both, and a vehicle's home district is optional.

## Identity and access

Three tables cover tenants, the people who log in, and the machine keys the simulator uses to push telemetry. A user belongs to exactly one tenant, which keeps the JWT's `tenant_id` claim unambiguous.

### tenants

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | uuid | PK |  |
| `name` | text | NN, CK non-empty after trim | Display name |
| `slug` | text | NN, UQ, CK lowercase letters, digits, hyphens | Used in URLs and the demo seed |
| `created_at` | timestamptz | NN, default `now()` |  |

### users

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | uuid | PK |  |
| `tenant_id` | uuid | NN, FK `tenants(id)` |  |
| `email` | citext | NN, UQ (global) | Global uniqueness lets login find the user before the tenant is known |
| `password_hash` | text | NN | Salted hash only, never the password |
| `display_name` | text | NN |  |
| `role` | text | NN, CK in (`operator`, `viewer`) | Two roles, as agreed |
| `created_at` | timestamptz | NN, default `now()` |  |
| `disabled_at` | timestamptz | nullable | Null means active; set instead of deleting, so history keeps its owner |
|  |  | UQ `(tenant_id, id)` | Target for composite foreign keys from dashboards |

### api\_keys

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | uuid | PK |  |
| `tenant_id` | uuid | NN, FK `tenants(id)` |  |
| `name` | text | NN, UQ `(tenant_id, name)` | Human label such as "simulator" |
| `key_prefix` | text | NN, UQ | Non-secret lookup token, so ingestion finds the row without scanning hashes |
| `key_hash` | text | NN | Hash of the secret part; the full key is shown once at creation and never stored |
| `created_at` | timestamptz | NN, default `now()` |  |
| `last_used_at` | timestamptz | nullable | Null until first use |
| `revoked_at` | timestamptz | nullable, CK null or `>= created_at` | Revoke instead of delete |

Login and API-key lookup both happen before the tenant is known, so they cannot go through the normal tenant filter. The isolation section covers how they are handled safely.

## Fleet: districts and vehicles

Districts are named geofences used for outage scenarios and "which vehicles are inside" queries. Vehicles are the stable identity of each simulated unit; their moving state lives in telemetry and Redis, not here.

### districts

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | uuid | PK |  |
| `tenant_id` | uuid | NN, FK `tenants(id)` |  |
| `name` | text | NN, UQ `(tenant_id, name)` |  |
| `boundary` | geography(Polygon, 4326) | NN, CK `ST_IsValid(boundary::geometry)` | Read back as GeoJSON text through `ST_AsGeoJSON`, since R2DBC has no geometry mapping |
| `created_at` | timestamptz | NN, default `now()` |  |
|  |  | UQ `(tenant_id, id)` | Target for composite foreign keys |
|  |  | GiST index on `boundary` | Supports containment queries |

### vehicles

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | uuid | PK |  |
| `tenant_id` | uuid | NN, FK `tenants(id)` |  |
| `external_ref` | text | NN, UQ `(tenant_id, external_ref)` | The device or simulator's own identifier, which makes ingestion idempotent per vehicle |
| `kind` | text | NN, CK in a fixed list | Value list is set when the demo's vehicle type is chosen (see open decisions) |
| `home_district_id` | uuid | nullable, FK `(tenant_id, home_district_id)` to `districts(tenant_id, id)` | Null when a vehicle has no home district |
| `status` | text | NN, CK in (`idle`, `active`, `charging`, `offline`, `fault`) | Last known status, refreshed from telemetry |
| `created_at` | timestamptz | NN, default `now()` |  |
| `retired_at` | timestamptz | nullable | Set instead of deleting, so old telemetry and alerts keep a valid parent |
|  |  | UQ `(tenant_id, id)` | Target for composite foreign keys from telemetry and alerts |

The `status` column is a convenience copy. The authoritative latest state, including position and battery, is the Redis latest-state hash, which can be rebuilt from telemetry if Redis is lost.

## Telemetry

Telemetry is the one high-volume table, so it is a TimescaleDB hypertable partitioned by `ts`. Its design is shaped by three hypertable rules: any unique constraint must include the time column, other tables can be referenced from a hypertable but a hypertable cannot be the target of a foreign key, and continuous aggregates cannot carry row-level security.

### telemetry (hypertable)

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `tenant_id` | uuid | NN | Part of the composite foreign key below |
| `vehicle_id` | uuid | NN |  |
| `ts` | timestamptz | NN | Event time reported by the vehicle; the partitioning column |
| `seq` | bigint | NN, CK `>= 0` | Per-vehicle sequence number, assigned by the server (see Redis layout) |
| `lat` | double precision | NN, CK between -90 and 90 |  |
| `lon` | double precision | NN, CK between -180 and 180 |  |
| `heading_deg` | real | nullable, CK `>= 0` and `< 360` | Null for stationary units such as chargers |
| `speed_kmh` | real | nullable, CK `>= 0` | Null when not applicable |
| `battery_pct` | smallint | NN, CK between 0 and 100 |  |
| `status` | text | NN, same CK list as `vehicles.status` | Status at the time of the sample |
|  |  | PK `(vehicle_id, ts)` | Includes the time column, as hypertables require |
|  |  | FK `(tenant_id, vehicle_id)` to `vehicles(tenant_id, id)` | Hypertable to ordinary table is allowed |

**Keys and indexes**

- The primary key doubles as the per-vehicle history index, and makes retried batches idempotent: inserts use `ON CONFLICT (vehicle_id, ts) DO NOTHING`.
- `seq` is stored for ordering and debugging but is not unique on its own, because a unique index on a hypertable must include `ts`. Order by `ts`, then `seq`.
- One extra index on `(tenant_id, ts DESC)` serves tenant-wide recent queries. Every added index slows batched writes, so more are added only when a measured query needs them.
- The foreign key adds a lookup per inserted row. It stays for integrity, and is the first thing to reconsider if the k6 test shows write cost; the ingestion layer already validates vehicles against a cached set.
- No stored geometry column. Geofence queries build the point from `lat` and `lon` in SQL over a short recent window.

**Chunking and retention.** These are starting values to tune against the load test, not final numbers: one-day chunks, and a retention policy that drops raw chunks after a window measured in days, not months, because the aggregates below carry the long view.

**No compression on this table.** TimescaleDB's documentation states that [row-level security is not supported on compressed chunks](https://www.tigerdata.com/docs/api/latest/compression). Row-level security is the isolation guarantee this project showcases, so raw telemetry stays uncompressed and short-lived. If storage ever forces compression, the fallback is to drop row-level security on this one table and isolate it through tenant-scoped views and repository filtering instead.

### Continuous aggregates

| Aggregate | Grouped by | Columns | Feeds |
| --- | --- | --- | --- |
| `telemetry_1m` | `tenant_id`, `vehicle_id`, 1-minute bucket | average and minimum battery, average and maximum speed, sample count | Per-vehicle charts |
| `telemetry_fleet_1m` | `tenant_id`, 1-minute bucket | average and minimum battery, sample count | Fleet tiles and the main live chart |

- Real-time aggregation is enabled so the newest, not-yet-materialized minute still appears.
- Aggregates keep their own, longer retention than raw telemetry.
- Aggregates cannot use row-level security, so the application role has no direct access to them. It reads through tenant-scoped views that filter on the current tenant setting (see Tenant isolation).

## Alerts

Alerts are ordinary rows with a small state machine (open, acknowledged, resolved). Because they are not high-volume, they stay a plain table and carry real foreign keys to vehicles and districts, which a hypertable could not be referenced by.

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | uuid | PK |  |
| `tenant_id` | uuid | NN, FK `tenants(id)` |  |
| `vehicle_id` | uuid | nullable, FK `(tenant_id, vehicle_id)` to `vehicles(tenant_id, id)` | Null for district-wide alerts |
| `district_id` | uuid | nullable, FK `(tenant_id, district_id)` to `districts(tenant_id, id)` | Null for single-vehicle alerts |
| `kind` | text | NN, CK in a fixed list | For example low battery, vehicle offline, district outage |
| `severity` | text | NN, CK in (`info`, `warning`, `critical`) |  |
| `status` | text | NN, default `open`, CK in (`open`, `acknowledged`, `resolved`) |  |
| `opened_at` | timestamptz | NN, default `now()` |  |
| `acknowledged_at` | timestamptz | nullable |  |
| `resolved_at` | timestamptz | nullable |  |
| `detail` | jsonb | NN, default empty object | Free-form context such as the battery level that triggered it |
|  |  | CK at least one of `vehicle_id`, `district_id` is not null | An alert must point at something |
|  |  | CK `(status = 'resolved') = (resolved_at IS NOT NULL)` | Status and timestamp cannot disagree |
|  |  | CK `resolved_at IS NULL OR resolved_at >= opened_at` |  |
|  |  | UQ `(tenant_id, id)` |  |

**Indexes.** `(tenant_id, status, opened_at DESC)` serves the alert list and the open-alert counter. A partial unique index on `(tenant_id, vehicle_id, kind)` where `status <> 'resolved'` stops the same condition from opening duplicate alerts for one vehicle while one is already active.

## Dashboards and widgets

A dashboard is owned by one user and can be shared read-only with the rest of the tenant. Each widget stores its own grid position, which is what react-grid-layout saves, so a layout is just the set of widget rows.

### dashboards

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | uuid | PK |  |
| `tenant_id` | uuid | NN, FK `tenants(id)` |  |
| `owner_id` | uuid | NN, FK `(tenant_id, owner_id)` to `users(tenant_id, id)` | Same-tenant owner is guaranteed by the composite key |
| `name` | text | NN, UQ `(tenant_id, owner_id, name)`, CK non-empty after trim |  |
| `is_shared` | boolean | NN, default false | True lets every user in the tenant view it; only the owner or an operator can edit |
| `created_at` | timestamptz | NN, default `now()` |  |
| `updated_at` | timestamptz | NN, default `now()` | Set by the application on every save |
|  |  | UQ `(tenant_id, id)` |  |

### dashboard\_widgets

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | uuid | PK |  |
| `tenant_id` | uuid | NN |  |
| `dashboard_id` | uuid | NN, FK `(tenant_id, dashboard_id)` to `dashboards(tenant_id, id)`, ON DELETE CASCADE | Widgets die with their dashboard |
| `type` | text | NN, CK in (`map`, `live_chart`, `status_tiles`, `alert_list`) | Extend the list with a migration when a widget type is added |
| `config` | jsonb | NN, default empty object | Widget settings such as which metric a chart shows |
| `grid_x` | integer | NN, CK `>= 0` |  |
| `grid_y` | integer | NN, CK `>= 0` |  |
| `grid_w` | integer | NN, CK `>= 1` |  |
| `grid_h` | integer | NN, CK `>= 1` |  |
| `created_at` | timestamptz | NN, default `now()` |  |

The database does not stop two widgets from overlapping on the grid. The layout library already resolves collisions on the client, so the extra database rule is not worth its complexity.

## Tenant isolation

Isolation rests on four pieces: separate database roles, one uniform row-level security policy, a tenant setting scoped to each transaction, and a few narrow functions for the lookups that happen before the tenant is known.

### Roles

| Role | Used for | Rules |
| --- | --- | --- |
| `app_owner` | Owns the tables and runs Flyway migrations | Never used by the running backend |
| `app_runtime` | The backend's connection pool | Not the owner, no `BYPASSRLS`, granted only the access it needs |
| `app_lookup` | Owns the two lookup functions below | Has `BYPASSRLS`, but can only be reached through those functions |

### The policy

Every tenant-owned table has row-level security enabled and forced, so even the table owner is subject to it. One policy per table covers all commands, with both `USING` and `WITH CHECK` set to the same test: `tenant_id` equals the tenant setting, read as `NULLIF(current_setting('app.tenant_id', true), '')::uuid`. When no tenant is set the comparison is null, so the query sees no rows and cannot write any. It fails closed.

### Setting the tenant under R2DBC

- Every request or ingestion batch runs inside a transaction whose first statement is `set_config('app.tenant_id', <tenant>, true)`. The final `true` makes it transaction-local.
- Never use a session-level `SET`. R2DBC reuses pooled connections, so a session setting would leak one request's tenant into the next.
- The tenant value comes only from the verified JWT claim, or from the API key's row for ingestion. A single ingestion batch always belongs to one tenant, so it is one transaction.

### Lookups before the tenant is known

- **Login.** A function `login_lookup(email)` returns only the fields login needs: user id, tenant id, password hash, role, and disabled flag.
- **API keys.** A function `api_key_lookup(key_prefix)` returns key id, tenant id, key hash, and revoked flag.
- Both are `SECURITY DEFINER`, owned by `app_lookup`, with a fixed `search_path`, and `EXECUTE` is granted to `app_runtime` alone. The backend then verifies the password or key hash itself and sets the tenant from the result.

### Continuous aggregates

Aggregates cannot carry row-level security. `app_runtime` is denied direct access to `telemetry_1m` and `telemetry_fleet_1m`, and reads two views instead, each marked `security_barrier` and filtered on the same tenant setting. The views run with their owner's privileges, so the runtime role needs no rights on the aggregates themselves.

### What the database does not enforce

Operator versus viewer permissions stay in the application layer, because the database has no notion of which HTTP route is calling it. Redis keys, channels, and tile registries live outside PostgreSQL, so they are tenant-namespaced instead (next section).

## Redis layout

Redis holds everything the live map needs in milliseconds: each vehicle's latest state, a geo index for viewport snapshots, and the pub/sub channels that carry updates between backend instances. It has no row-level security, so every key and channel starts with the tenant ID.

| Purpose | Name | Type | Contents |
| --- | --- | --- | --- |
| Latest vehicle state | `t:<tenant>:v:<vehicle>` | hash | `lat`, `lon`, `heading`, `speed`, `battery`, `status`, `ts`, `seq` |
| Geo index | `t:<tenant>:geo` | geo sorted set | Vehicle IDs by position; answers viewport snapshot queries with a box search |
| Tile counts | `t:<tenant>:tc:<zoom>` | hash | Field `<x>:<y>` to vehicle count; feeds cluster counts when zoomed out |
| Updates channel | `t:<tenant>:ch:updates` | pub/sub | Tenant-wide vehicle updates (stage 3 of the build plan) |
| Tile channels | `t:<tenant>:ch:tile:<z>:<x>:<y>` | pub/sub | Per-tile updates, added when per-tile fan-out replaces the tenant-wide channel |
| Alerts channel | `t:<tenant>:ch:alerts` | pub/sub | New and changed alerts for the SSE stream |

### Sequence numbers

The server assigns each vehicle's `seq`, using an atomic increment on the `seq` field of its latest-state hash. Because the increment is atomic, two backend instances can ingest for the same vehicle without issuing duplicates. The client keeps only the highest `seq` it has seen per vehicle, which resolves the snapshot-versus-delta race. If Redis is lost, the hashes are rebuilt from the latest telemetry row per vehicle, and `seq` resumes from the highest stored value.

### Write order at ingestion

1. Validate the batch and resolve the tenant from the API key.
2. Assign `seq` and update the latest-state hash, geo index, and tile counts.
3. Publish the update to the channel.
4. Persist the batch to TimescaleDB, with retries on failure.

The live path runs first for low latency, and persistence follows in batches. Redis is the live view and TimescaleDB is the history, so a brief gap between them is acceptable and a failed batch is retried rather than blocking the map.

### Tile scheme

The proposed scheme is slippy-map tiles (`z/x/y`) at one fixed subscription zoom. They match MapLibre's own tile grid, need only simple latitude and longitude arithmetic, and add no extra library. H3 hexagons give more uniform areas and tidier clusters, but cost a dependency and a mapping step. The subscription zoom itself is set in stage 4 from observed vehicle density.

## Tests, migration order, and open decisions

Every constraint above is proved by a Testcontainers integration test against real TimescaleDB and PostGIS, so the model is verified rather than assumed.

### Constraint tests

| Test | Proves |
| --- | --- |
| Cross-tenant read | Tenant A's session sees zero rows from tenant B on every tenant-owned table |
| No tenant set | Reads return nothing and writes are rejected |
| Cross-tenant write | Inserting a row carrying another tenant's `tenant_id` violates the policy |
| Cross-tenant reference | A dashboard owned by another tenant's user fails the composite foreign key |
| Pooled connection reuse | Two requests for different tenants on the same pooled connection never see each other's rows |
| Check constraints | Out-of-range `lat`, `lon`, or `battery_pct`, an unknown status, and a resolved alert with no `resolved_at` are all rejected |
| Idempotent ingestion | Sending the same batch twice leaves one row per `(vehicle_id, ts)` |
| Aggregate access | The runtime role is denied direct access to aggregates, and the views return only the current tenant's rows |
| Lookup functions | They return only the listed fields, and only for an exact email or key prefix |
| RLS coverage guard | A test lists every table with a `tenant_id` column and fails the build if any lacks an enabled, forced policy |

### Migration order

Each tenant-owned table's migration also creates its policy and grants, so no table ever exists without isolation.

1. Extensions, roles, and the tenant-setting convention
2. `tenants`, `users`, `api_keys`, and the two lookup functions
3. `districts` and `vehicles`
4. `telemetry` hypertable, indexes, and retention policy
5. Continuous aggregates, their policies, and the tenant-scoped views
6. `alerts`
7. `dashboards` and `dashboard_widgets`
8. Demo seed data: two tenants, so the isolation story is visible on day one

### Open decisions

| Decision | Current default | Revisit when |
| --- | --- | --- |
| Tile scheme | Slippy-map tiles at one fixed zoom | Before the map channel is built (stage 3 to 4) |
| Vehicle `kind` values | Left open until the demo's vehicle type is chosen | Before the `vehicles` migration |
| Telemetry foreign key | Kept, for integrity | If the k6 test shows write cost |
| Raw retention window and chunk size | Days of retention, one-day chunks | After measuring storage and query cost |
| District-wide alert deduplication | Only per-vehicle alerts are deduplicated; a second partial index would cover district alerts | When the outage scenario is built |
| Compression | Off, because compressed chunks do not support row-level security | Only if storage forces it, with the fallback described under Telemetry |
