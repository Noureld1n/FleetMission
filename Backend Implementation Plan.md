# Fleet Mission Control: Backend Implementation Plan

This plan turns every requirement in `Backend Sheet.md` into an ordered implementation path. Each phase has concrete work and a completion gate so the backend can be built and reviewed incrementally.

## Implementation rules used throughout

- Keep the backend on Spring Boot 4.x, Java 21 or 25, Maven, and the reactive WebFlux stack. Use the latest stable Boot release available when starting work; never use a milestone, release candidate, or snapshot.
- Keep the project coordinates and root package aligned: `com.fleetcontrol:backend`, package `com.fleetcontrol.backend`.
- Keep the application reactive end to end. Do not add Spring MVC/WebMVC, JPA, Spring Data JDBC, or blocking JDBC access in request paths. JDBC is reserved for Flyway.
- Controllers handle transport and validation, services own business rules and transactions, repositories own database/Redis access, models stay transport-independent, DTOs are immutable records, and mappers are pure conversions.
- Never call `block()`, `Thread.sleep`, or manually subscribe from ordinary request/service code. Only managed ingestion and Redis-listener pipeline roots may subscribe, and they must start and stop with application lifecycle.
- Carry user and tenant identity in Reactor Context, never ThreadLocal. Every tenant-owned database operation runs in a transaction that sets the transaction-local tenant setting as its first statement.
- Bound all queues and buffers. Name and checkpoint important pipelines. Keep CPU-heavy password hashing off event-loop threads.
- Use one global error handler for domain errors and problem-details responses. Do not put business rules in controllers or repository code.
- Keep secrets and connection details in environment variables. Never commit signing keys, passwords, or API-key secrets.

## Phase 0: Project baseline and dependency alignment

### Work

- [x] Confirm the generated project uses Maven, Java 21 or 25, JAR packaging, the stable Spring Boot 4.x line, and `com.fleetcontrol.backend`.
- [x] Confirm production dependencies: WebFlux, R2DBC, PostgreSQL JDBC and R2DBC drivers, reactive Redis/Lettuce, Spring Security, OAuth2 Resource Server/JWT, Validation, Actuator, Micrometer Prometheus, Flyway starter, JDBC starter, and Flyway PostgreSQL support.
- [x] Confirm test dependencies: Boot test companions, Spring Boot Testcontainers support, Testcontainers JUnit Jupiter, PostgreSQL and R2DBC modules, Testcontainers core for Redis `GenericContainer`, Reactor Test, and BlockHound.
- [x] Verify BlockHound's JVM launch requirements for the selected JDK and configure test execution accordingly.
- [x] Confirm `BackEnd/src/main/resources/db/migration` as the Flyway migration location. Move/copy the existing schema draft there only after checking it against the model and migration sequence in Phase 1.
- [x] Create profile files: `application.yml`, `application-dev.yml`, and `application-prod.yml`.
- [x] Keep the existing application name `backend` and main class `BackendApplication`.

### Completion gate

- [x] Project builds with the selected JDK and all dependencies resolve.
- [ ] A minimal application context starts without adding MVC/JPA or blocking database access.
- [x] No secrets are present in committed configuration.

## Phase 1: Schema review, database roles, migrations, and seed data

### Work

- [x] Review the current SQL draft against `Data Model.md` and this plan. The initial migrations keep the draft's concrete kind lists and 1-day/14-day/365-day retention values as documented starting choices; district-wide alert deduplication remains open. The exact TimescaleDB refresh behavior with forced source-table RLS remains a verification gate.
- [x] Place migrations in `src/main/resources/db/migration` and split them into ordered Flyway migrations:
  1. Extensions, roles, schemas, and tenant-setting convention.
  2. Tenants, users, API keys, and narrowly scoped login/API-key lookup functions.
  3. Districts and vehicles.
  4. Telemetry hypertable, indexes, retention.
  5. Continuous aggregates, their retention, and tenant-filtered views.
  6. Alerts and indexes.
  7. Dashboards and dashboard widgets.
  8. Demo seed data for two tenants.
  9. Additive alert change timestamps for SSE replay (`V10`).
- [x] Use PostgreSQL extensions required by the data model: TimescaleDB, PostGIS, `citext`, and UUID generation support. Provision extensions and cluster roles through the administrator-run `DataBase/bootstrap.sql` before Flyway.
- [x] Create separate `app_owner`, `app_runtime`, and `app_lookup` roles. The running backend uses `app_runtime`; migrations use `app_owner`; lookup functions are narrowly exposed to runtime.
- [ ] Use UUID primary keys, UTC `timestamptz`, snake_case/plural naming, text plus CHECK constraints for enumerations, and `NOT NULL` except where nullable behavior is documented.
- [ ] Ensure every tenant-owned table has `tenant_id`, a same-tenant composite uniqueness target where required, and composite foreign keys for tenant-owned parent references.
- [ ] Implement identity data: global case-insensitive unique user email, password hashes only, user role and disabled timestamp, API-key prefix/hash, and revocation/last-use timestamps.
- [ ] Implement districts with valid PostGIS geography polygons and a GiST index. Exchange geometry with application code as plain values/GeoJSON SQL output rather than R2DBC geometry objects.
- [ ] Implement vehicles with tenant-scoped external reference, chosen `kind` constraint, optional same-tenant home district, latest status copy, and retirement timestamp.
- [ ] Implement telemetry as a Timescale hypertable on event time `ts`, with `(vehicle_id, ts)` primary key, server-assigned per-vehicle `seq`, field range checks, same-tenant vehicle FK, tenant/time index, idempotent insert behavior, and selected retention/chunk policies.
- [ ] Implement `telemetry_1m` and `telemetry_fleet_1m` continuous aggregates with the required battery/speed/count values, real-time reads, and longer retention than raw telemetry.
- [ ] Implement alerts with optional vehicle and district references (at least one required), kind/severity/state constraints, timestamps, JSON details, list index, and active per-vehicle/kind deduplication index. Decide and document whether district-wide deduplication is needed for outage behavior.
- [ ] Implement dashboards and widgets with tenant-scoped ownership, sharing flag, widget configuration and grid coordinates, and cascading widget deletion. Leave overlap validation to the layout library.
- [x] Apply RLS enabled and forced policies to every tenant-owned table. Policy `USING` and `WITH CHECK` compare `tenant_id` with the transaction-local `app.tenant_id` setting and fail closed when it is absent.
- [x] Ensure the runtime role is not table owner, has no `BYPASSRLS`, and receives only required grants. Lookup function ownership and search paths are constrained.
- [x] Implement exact-email login lookup and API-key-prefix lookup functions that return only required fields. Do not use general tenant-filtered access before tenant identity is established.
- [x] Deny runtime direct access to aggregates. Expose them only through security-barrier tenant-filtered views using the current tenant setting.
- [x] Add a schema coverage check that detects any tenant-owned table without enabled/forced RLS and a tenant policy.

### Completion gate

- [ ] Flyway can apply the migration sequence to the intended TimescaleDB/PostGIS image. It has not been run against a clean database yet.
- [ ] Two seeded tenants exist. Demo users/API keys are deferred until the credential creation flow exists, so no reusable hashes or credentials are embedded in SQL.
- [ ] Schema constraints, RLS, role grants, lookup functions, aggregates, and tenant views match the model before feature code depends on them; scheduled aggregate refresh under forced telemetry RLS still needs verification against the target image.

## Phase 2: Application structure, configuration, errors, and observability base

### Package structure

Create the following under `com.fleetcontrol.backend`, splitting a package by feature only if it grows large:

```text
config/          security, redis, r2dbc, websocket, JSON, properties
controller/      REST, SSE, ingestion, WebSocket handlers
service/         business rules and reactive pipelines
repository/      R2DBC SQL and Redis access
model/           entities, enums, value objects
dto/request/     validated request records
dto/response/    response records
dto/message/     SSE and WebSocket message records
mapper/          pure model-to-DTO conversions
security/        JWT, API-key handling, tenant context
exception/       domain exceptions and global error mapping
util/            tile math and time helpers
```

### Work

- [x] Bind custom settings in typed configuration classes under `app`: instance ID; JWT secret, issuer and TTL; CORS origins; ingestion queue capacity, batch size and maximum wait; map tile zoom, flush interval and auth timeout; alert battery threshold and offline duration.
- [x] Configure environment-driven R2DBC runtime credentials (`app_runtime`) and separate Flyway JDBC credentials (`app_owner`).
- [x] Configure Redis host/port through `SPRING_DATA_REDIS_HOST` and `SPRING_DATA_REDIS_PORT`.
- [x] Configure server port from `SERVER_PORT`, Prometheus/health exposure from `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`, and bind actuator to an internal management address/port by default.
- [x] Configure dev profile for local Compose, verbose logs and local frontend CORS; prod for strict CORS, minimal logs and internal actuator access.
- [x] Expose only health and Prometheus actuator endpoints by default. Enable liveness/readiness probes and suppress public health details.
- [x] Add a global problem-details handler for validation and domain errors, including generic 401/403 responses. Phase 3 connects matching handlers to the reactive Spring Security entry point and access-denied handler.
- [x] Configure Micrometer/Prometheus base tags including `app.instance-id` so multiple backend instances can be distinguished.
- [x] Keep SQL migration execution in Flyway; do not also initialize the schema through `schema.sql`, R2DBC schema scripts, or ORM DDL.

### Completion gate

- [ ] Each profile starts with configuration supplied from its environment and no hard-coded secrets. Startup has not been checked because Maven currently cannot acquire a lock for the Spring Boot parent in the local `.m2` repository.
- [ ] Health and Prometheus endpoints are available only as intended; domain and validation errors have one response format. The reactive Security handlers are wired, but runtime exposure has not yet been checked.

## Phase 3: Reactive tenant context, RLS transaction boundary, authentication, and authorization

### Work

- [x] Implement user login at `POST /api/auth/login` using the `login_lookup` function, BCrypt password hashes, and verification off the event loop on bounded elastic.
- [x] Implement self-issued short-lived JWTs with `sub` user UUID, `tenant_id`, `role`, `iat`, `exp`, and configured issuer. Load the signing key only from `APP_JWT_SECRET`.
- [x] Configure reactive Spring Security JWT validation for bearer tokens and role checks.
- [x] Implement a separate API-key authentication path for `/ingest/v1/**`: look up by prefix, compare a constant-time SHA-256 secret hash, reject unknown/revoked/mismatched keys, then resolve the tenant from the key record.
- [x] Keep login and API-key pre-tenant lookup limited to the database functions; do not broaden unauthenticated database access.
- [x] Implement ordered security chains/path matchers that distinguish user JWT traffic from machine API-key traffic.
- [x] Put tenant ID, subject ID, and role in a typed Reactor Context value. Never accept tenant identity from body, path, or query parameters, and never use ThreadLocal.
- [x] Add a shared transaction helper used by repositories/services. Inside each tenant-owned transaction, its first SQL statement sets `app.tenant_id` transaction-locally; never set a pooled connection's session-level tenant.
- [x] Route the currently implemented tenant-owned transaction boundary through the shared helper; no other tenant-owned database code exists yet.
- [ ] When background pipeline roots are added, set tenant identity from each message before database work.
- [x] Apply endpoint access rules: viewers can read; operators inherit viewer access and can write. Resource-specific owner rules remain in domain services.
- [x] Restrict CORS to configured frontend origins. Add Redis-backed login rate limiting. Bind management endpoints to a loopback-only port by default.
- [x] Define the `/ws/map` auth timeout and a helper that validates the JWT from its first auth frame. The WebSocket handler will enforce frame shape and close behavior in Phase 8.
- [x] Return uniform RFC problem-details responses for credential, authorization, domain, and validation failures without exposing hashes or token details.

### Completion gate

- [ ] Login issues and validates the documented claims; disabled users, expired/invalid tokens, wrong roles, unknown/revoked API keys are rejected. Automated security checks have not been run.
- [ ] A tenant-owned database operation with no context sees no rows and cannot write; each request/transaction sets context on its own transaction. Runtime database verification has not been run.

## Phase 4: Core REST domain and dashboard foundation

### Work

- [x] Define model enums/value objects from the schema constraints, including status, role, alert state/severity, and selected vehicle/alert kinds.
- [x] Implement immutable request/response DTOs with validation annotations. Never expose R2DBC entities, password hashes, raw API-key hashes, or internal fields.
- [x] Implement repository SQL with tenant context for vehicles, districts, alerts, dashboards, widgets, and aggregate reads. Use `DatabaseClient`/SQL for PostGIS expressions and aggregate views where needed.
- [x] Implement mappers as pure functions only.
- [x] Implement `GET /api/me` for the authenticated user, role and tenant.
- [x] Implement vehicle reads: `GET /api/vehicles` with status/district filters and `GET /api/vehicles/{id}` with latest state from Redis, with a defined behavior if cache is absent (latest state is `null`).
- [x] Implement district reads and `POST /api/districts`; return boundaries through SQL `ST_AsGeoJSON` and accept validated GeoJSON/coordinates without exposing PostGIS types through R2DBC.
- [x] Implement alerts list `GET /api/alerts` with status/severity filters, plus `POST /api/alerts/{id}/acknowledge` and `/resolve` with state transition rules.
- [x] Implement dashboard visibility rules: an owner sees their dashboards; tenant members can see shared dashboards; only the owner can edit, with the existing operator route authorization.
- [x] Implement `GET /api/dashboards`, `GET /api/dashboards/{id}`, `POST /api/dashboards`, `PUT /api/dashboards/{id}` replacing name/share/layout, and `DELETE /api/dashboards/{id}` with widget cascade.
- [x] Use cursor and `limit` for list routes; timestamps are ISO 8601 UTC; IDs are UUIDs; request bodies are JSON.
- [x] Use service methods for business rules and orchestration. Controllers only route, validate, read security context and call one service method.

### Completion gate

- [ ] Foundation CRUD works for both seeded tenants, with tenant isolation and viewer/operator/owner behavior enforced.
- [ ] Validation failures and missing/forbidden records use the shared problem-details format.

## Phase 5: Telemetry ingestion and persistence pipeline

### Work

- [x] Define telemetry request DTOs and enforce batch-size and all field-range constraints before queueing. Requests are capped at 500 events and the configured batch size.
- [x] Implement `POST /ingest/v1/telemetry` authenticated by API key. Resolve tenant only from the authenticated key and reject events referencing vehicles outside that tenant.
- [x] Maintain/cache the tenant's valid vehicle ID set and define refresh/invalidation behavior when vehicle records change. The tenant-keyed cache refreshes after 30 seconds and exposes explicit invalidation.
- [x] Assign per-vehicle sequence numbers with an atomic Redis increment. On a missing hash, read the latest persisted telemetry row and seed state/sequence before applying the new event.
- [x] Define Redis key names exactly as tenant-scoped in the data model: latest vehicle hashes, tenant geo index, tile-count hashes, update channel, optional tile channels, and alert channel.
- [x] Update Redis latest state, geo index and tile counts, then publish vehicle updates. Tenant IDs prefix all live and recovery keys/channels.
- [x] Queue accepted events into a bounded shared Reactor sink and return `202 Accepted` only when queued according to the API contract.
- [x] Define failure behavior: queue capacity is reserved before Redis mutation; queue-full/Redis errors return a problem response; database writes retry five times with capped exponential backoff, then move to a bounded tenant recovery list. If recovery storage is unavailable/full, the batch remains in a bounded in-memory held queue and consumes queue capacity until it can be stored or persisted. Duplicate `(vehicle_id, ts)` inserts are ignored.
- [x] Group persistence work by tenant before buffering so each database transaction has one tenant. Use `bufferTimeout` with configured size/time bounds and one multi-row insert statement per batch.
- [x] Use conflict-ignore on `(vehicle_id, ts)` for idempotent retries. Retry transient failures with a bounded strategy; exhausted failures are measured/logged and retained in recovery storage or the capacity-reserved held queue.
- [x] Keep the TimescaleDB write as the history path and Redis as latest-state/live path: validate, update/publish Redis, enqueue, then persist history.
- [x] Evaluate low-battery and offline rules, resolve alerts when conditions clear, and publish alert changes. A periodic stale-telemetry sweep applies `app.alerts.offline-after`.
- [x] Start and stop the sink/persistence subscriptions through a lifecycle-managed bean. Name/checkpoint the pipeline and bound buffers/concurrency.
- [x] Add ingestion metrics for events received/accepted/rejected/persisted, queue depth/capacity/drops, batch size/write latency, retry attempts, recovery saturation, and terminal failures.

**Acceptance guarantee:** `202 Accepted` means the batch passed validation and tenant ownership checks, its Redis live updates were applied/published, and it entered the bounded in-process sink. Persistence is asynchronous. Database outages are retried and then recovered through Redis or a capacity-reserved in-memory held queue. An abrupt process loss before a batch reaches PostgreSQL or Redis recovery storage can still lose that in-memory batch; the completion gate therefore requires runtime failure testing before claiming durable acceptance.

### Completion gate

- [ ] A valid simulator-style batch is accepted and saved; retrying it does not add duplicate `(vehicle_id, ts)` rows. This has not been exercised against PostgreSQL/Redis.
- [ ] Invalid batches, cross-tenant vehicle references, API-key errors, queue saturation and dependency failures have explicit observable outcomes. Runtime security and failure-path behavior remains unverified.

## Phase 6: Live telemetry reads, metrics, and alert persistence

### Work

- [x] Implement per-vehicle history `GET /api/vehicles/{id}/history` using `telemetry_1m` tenant-filtered view.
- [x] Implement fleet chart `GET /api/metrics/fleet` using `telemetry_fleet_1m` tenant-filtered view.
- [x] Confirm migration grants deny runtime direct aggregate access and repository queries use only tenant-filtered security-barrier views. Live role grants have not yet been checked against the database.
- [x] Persist alerts with valid same-tenant vehicle/district foreign keys and the specified state/timestamp consistency constraints. Vehicle alerts are created inside tenant transactions; database constraints enforce same-tenant references and timestamps.
- [x] Implement alert creation/update idempotency and active per-vehicle/kind deduplication. District-wide deduplication remains for the outage-scenario phase.
- [x] Review alert-list metrics; no extra alert-list counters are needed yet. Existing ingestion and alert processing metrics have no tenant labels.
- [x] Ensure chart/history APIs use a default 24-hour time range with a strict seven-day maximum and a bounded result size.

### Completion gate

- [ ] Aggregate reads return only the current tenant's metrics and include the newest real-time aggregate interval. Runtime behavior has not yet been checked against TimescaleDB.
- [ ] Alert state transitions remain consistent in the database and produce events for downstream streaming. Database and Redis behavior has not yet been exercised.

## Phase 7: SSE event stream and replay behavior

### Work

- [x] Implement `GET /api/stream/events` for authenticated users using a fetch-based browser client contract because native `EventSource` cannot send Authorization headers.
- [x] Stream `alert.opened`, `alert.updated`, `fleet.metrics`, and periodic `heartbeat` events.
- [x] Use tenant-scoped Redis pub/sub for alert changes and aggregate polling/updates for metrics; keep all payloads as DTOs.
- [x] Set alert event IDs to change timestamps. On reconnect, use `Last-Event-ID` to query and page through alerts changed since that timestamp from PostgreSQL.
- [x] Do not replay metric events; metric events omit SSE IDs, so the next poll resumes the chart without affecting alert replay position.
- [x] Bound per-client buffering and clean up subscriptions on disconnect. Each replay page and aggregate read establishes tenant context in its transaction.
- [x] Expose SSE connection count and disconnect/error/overflow metrics; send 15-second heartbeats to keep intermediaries from closing idle streams.

**Schema addition:** Migration `V10__alert_change_timestamps.sql` adds `alerts.updated_at`. Alert open/update/transition writes refresh it, so replay and event IDs share a persisted timestamp. Replay uses `>= Last-Event-ID` to avoid losing alerts that share the same timestamp; clients may receive the last event again and should deduplicate by alert ID and timestamp.

**Client contract:** Use `fetch` with `Authorization: Bearer <token>` and, on reconnect, `Last-Event-ID: <UTC timestamp>` from the most recent alert event. Metric and heartbeat events omit IDs. Fleet metrics are polled every 15 seconds and can update the same minute bucket.

### Completion gate

- [ ] Client reconnect replays missed alert changes, receives future alert/metric updates, and cannot observe another tenant's events. Runtime Redis/PostgreSQL and browser reconnection behavior has not been exercised.

## Phase 8: Map WebSocket, snapshots, tile subscriptions, and conflation

### Work

  - [x] Implement `/ws/map` as a reactive WebSocket endpoint. First inbound message must be `auth` carrying JWT; enforce configured timeout and close unauthorized/late sockets; send `ready` after validation.
  - [x] Define typed inbound/outbound message records for `auth`, `viewport`, `ready`, `snapshot`, `delta`, `clusters`, and `error`.
  - [x] Require viewport messages to contain bounds, zoom, and `viewportVersion`. Echo that version on every snapshot/delta/cluster response so clients can discard stale viewport responses.
  - [x] Implement slippy-map tile math at a selected fixed subscription zoom. Validate bounds and zoom; handle antimeridian/poles and tile-count limits safely.
  - [x] Maintain an in-memory registry keyed by `(tenant, tile)` to local subscribers. Add/remove tile memberships on viewport changes and clean all memberships on disconnect/error.
  - [x] For newly entered tiles, load snapshot IDs from tenant Redis geo index, then read latest-state hashes and send a snapshot. Merge newer queued client-visible updates by sequence number.
  - [x] Use tile count hashes to send `clusters` instead of individual vehicles at low zoom; use individual vehicle states at high zoom.
  - [x] Share a tenant-wide Redis updates subscription per backend instance while local sockets for that tenant are active. Map each update to its tile and dispatch only to local subscribers.
  - [x] Give each socket a bounded outbound buffer keyed by vehicle ID. Keep only newest position update per vehicle; count conflated/dropped updates. Latest telemetry state includes status transitions.
  - [x] Flush each connection's coalesced updates at the configured fixed interval as batched `delta` messages. Bound the per-vehicle pending map and outbound queue.
  - [x] Manage Redis listener lifecycle with tenant socket reference counts; tenant identity comes from the verified JWT and is passed explicitly to the dispatcher.
  - [x] Server reconnect behavior is stateless: every connection authenticates again, and a new viewport request produces a fresh snapshot. No frontend client exists in this repository.
  - [x] Add metrics for open sockets, tiles subscribed, snapshot duration, queue size, flushes, conflated updates, and dropped updates.

### Completion gate

  - [ ] An authenticated client sees only its tenant's vehicles, receives fresh snapshots on viewport changes, gets deltas for subscribed tiles, ignores prior viewport versions, and remains memory-bounded when slow. Runtime verification against Redis and a WebSocket client remains outstanding.

## Phase 9: Scenario commands, outage flow, and end-to-end demo path

### Work

- [ ] Implement `POST /api/scenarios/outage` for operators. Validate district and tenant ownership.
- [ ] Persist a tenant-scoped scenario command for simulator pickup so the simulator continues to communicate only through the public API.
- [ ] Implement `GET /ingest/v1/commands` API-key-authenticated polling for pending simulator commands, with tenant scoping, acknowledgement/expiry rules and retry behavior.
- [ ] On outage, identify affected vehicles using district geometry/spatial queries and/or recent telemetry, open district/vehicle alerts as designed, and publish Redis updates.
- [ ] Update vehicle/status/latest state and relevant chart metrics as appropriate; ensure the UI can show the outage cascading to map, alert list, status tiles and charts.
- [ ] Add an end-to-end flow: simulator batch in, telemetry stored, vehicle update delivered to WebSocket, alert emitted to SSE, and outage command delivered to simulator.

### Completion gate

- [ ] Operator-triggered outage produces a visible, tenant-scoped end-to-end event chain without direct simulator/database coupling.

## Phase 10: Test coverage and reactive correctness

### Work

- [ ] Install BlockHound once through shared test support and account for its JVM flag/JDK constraints.
- [ ] Controller WebFlux slice tests with `WebTestClient`: routes, DTO validation, response codes, access rules, and mocked services using Boot 4's `@MockitoBean`/`@MockitoSpyBean` APIs.
- [ ] Service tests with `StepVerifier`: business rules, retry/backoff, batching, tenant grouping, sequence behavior, snapshot/delta handling, conflation and virtual-time timer behavior.
- [ ] Repository integration tests using TimescaleDB/PostGIS-compatible Testcontainers and Redis Testcontainers; exercise real SQL/Redis behavior, not substitutes that omit required extensions.
- [ ] Security integration tests: invalid/expired/wrong-role JWT, disabled user, unknown/revoked/wrong API key, and login/API-key lookup scope.
- [ ] Tenant isolation tests through API, WebSocket, direct database operations and Redis channel/key namespacing. Cover RLS no-tenant reads/writes, cross-tenant reads/writes/references, and pooled connection reuse.
- [ ] Check schema constraints for bad coordinates/battery/status, invalid alert state/timestamps, and invalid district geometry.
- [ ] Test aggregate direct access denial and tenant-filtered view results.
- [ ] Test RLS coverage guard for all tenant-owned tables.
- [ ] Test idempotent ingestion and reconnect/replay behavior.
- [ ] End-to-end test the simulator ingestion to stored row to visible map update flow, plus scenario command delivery.
- [ ] Keep tests focused by layer; prefer mocks/service tests for high layers and use real containers for database-specific behavior.

### Completion gate

- [ ] All required tests pass with BlockHound active; no blocking calls, tenant leaks, unbounded buffers, or unhandled reactive failures are known.

## Phase 11: Deployment readiness, dashboards, and performance proof

### Work

- [ ] Complete Docker Compose for PostgreSQL with TimescaleDB/PostGIS, Redis, two backend instances, simulator, Grafana, and frontend service integration as the project setup requires.
- [ ] Configure two backend instances with distinct `app.instance-id` and ports; verify Redis pub/sub delivers updates from one instance to sockets connected to the other.
- [ ] Keep separate migration/runtime credentials in Compose and deployment; migrate with `app_owner`, serve requests with `app_runtime`.
- [ ] Restrict management endpoints and database/Redis ports to internal networks; expose only intended public application routes.
- [ ] Configure Prometheus scraping and Grafana dashboards for ingestion rate, queue pressure, batch size/write latency, open WebSocket/SSE connections, subscribed tiles, snapshot duration, conflated updates, retries and failures.
- [ ] Add k6 load scenario for event ingestion and connected map clients. Record a measured headline result with test conditions; do not guess or advertise an unmeasured capacity.
- [ ] Tune retention, chunk size, query indexes, tile subscription zoom, batch size/wait, queue capacity and flush interval using measurements.
- [ ] Add GitHub Actions build/test pipeline and ensure the project is reproducible through one-command Docker Compose startup.
- [ ] Prepare seeded public demo access, README architecture diagram, short GIF/video, deployed link, and decisions/trade-offs explanation for WebFlux, R2DBC, WebSocket vs SSE, tile subscriptions, Redis fan-out and conflation.

### Completion gate

- [ ] Compose starts the demo with one command; two-instance fan-out is demonstrated; Grafana shows real metrics; the load-test headline is measured; demo and README materials are ready.

## Phase 12: Optional stretch work

Do these only after Phase 11 is complete and the core demo remains reliable.

- [ ] Replace tenant-wide Redis update subscriptions with per-tenant/per-tile channels only if measured fan-out requires it.
- [ ] Add replay slider over recent telemetry history.
- [ ] Expand alert rules beyond initial low-battery/offline/outage behavior.
- [ ] Add viewer presence such as number of users viewing a dashboard/map.
- [ ] Revisit telemetry compression only if storage pressure requires it; preserve the documented RLS guarantee or design an equally strong isolation model before enabling compression.
- [ ] Do not introduce CRDT collaboration, a custom query builder, Keycloak, Kafka, or other excluded scope without a demonstrated project need.

## Cross-phase API checklist

| Method | Path | Access | Implemented in |
| --- | --- | --- | --- |
| POST | `/api/auth/login` | Public, rate limited | Phase 3 |
| GET | `/api/me` | Viewer | Phase 4 |
| GET | `/api/vehicles` | Viewer | Phase 4 |
| GET | `/api/vehicles/{id}` | Viewer | Phase 4 |
| GET | `/api/vehicles/{id}/history` | Viewer | Phase 6 |
| GET | `/api/districts` | Viewer | Phase 4 |
| POST | `/api/districts` | Operator | Phase 4 |
| GET | `/api/metrics/fleet` | Viewer | Phase 6 |
| GET | `/api/alerts` | Viewer | Phase 4 |
| POST | `/api/alerts/{id}/acknowledge` | Operator | Phase 4 |
| POST | `/api/alerts/{id}/resolve` | Operator | Phase 4 |
| GET | `/api/dashboards` | Viewer | Phase 4 |
| GET | `/api/dashboards/{id}` | Viewer | Phase 4 |
| POST | `/api/dashboards` | Operator | Phase 4 |
| PUT | `/api/dashboards/{id}` | Operator, owner rules apply | Phase 4 |
| DELETE | `/api/dashboards/{id}` | Operator, owner rules apply | Phase 4 |
| POST | `/api/scenarios/outage` | Operator | Phase 9 |
| POST | `/ingest/v1/telemetry` | Tenant API key | Phase 5 |
| GET | `/ingest/v1/commands` | Tenant API key | Phase 9 |
| GET | `/api/stream/events` | Viewer JWT | Phase 7 |
| WebSocket | `/ws/map` | JWT in first message | Phase 8 |
| GET | `/actuator/health` | Internal | Phase 2 |
| GET | `/actuator/prometheus` | Internal | Phase 2 |

## Cross-phase operational and correctness checklist

- [ ] Tenant ID originates only from verified JWT or API-key lookup.
- [ ] Tenant ID is present in every tenant-owned row, Redis key, Redis channel, tile registry entry, subscription, and background message.
- [ ] RLS is enabled and forced on every tenant-owned table; runtime is not owner or `BYPASSRLS`.
- [ ] Every tenant-scoped transaction sets the transaction-local tenant value first on the same connection.
- [ ] No raw `ThreadLocal`, blocking call, unbounded queue, request-path subscription, cross-tenant cache lookup, or direct runtime aggregate read exists.
- [ ] Every external API response uses DTOs; every list is bounded; timestamps use UTC; errors use problem details.
- [ ] Every long-lived stream cleans up subscriptions/state on disconnect and has reconnect/resume behavior documented.
- [ ] Failure behavior is explicit for PostgreSQL, Redis, queue saturation, client backpressure, retries and replay.
- [ ] Metrics and tests support every performance/security claim shown in the demo or README.
