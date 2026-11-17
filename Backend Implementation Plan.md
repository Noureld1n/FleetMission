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

- [ ] Review the current SQL draft against `Data Model.md` and this plan. Resolve provisional vehicle/alert kinds, retention windows, chunk interval, and any ownership/grant details before treating the draft as authoritative.
- [ ] Place migrations in `src/main/resources/db/migration` and split them into ordered Flyway migrations:
  1. Extensions, roles, schemas, and tenant-setting convention.
  2. Tenants, users, API keys, and narrowly scoped login/API-key lookup functions.
  3. Districts and vehicles.
  4. Telemetry hypertable, indexes, retention.
  5. Continuous aggregates, their retention, and tenant-filtered views.
  6. Alerts and indexes.
  7. Dashboards and dashboard widgets.
  8. Demo seed data for two tenants.
- [ ] Use PostgreSQL extensions required by the data model: TimescaleDB, PostGIS, `citext`, and UUID generation support.
- [ ] Create separate `app_owner`, `app_runtime`, and `app_lookup` roles. The running backend uses `app_runtime`; migrations use `app_owner`; lookup functions are narrowly exposed to runtime.
- [ ] Use UUID primary keys, UTC `timestamptz`, snake_case/plural naming, text plus CHECK constraints for enumerations, and `NOT NULL` except where nullable behavior is documented.
- [ ] Ensure every tenant-owned table has `tenant_id`, a same-tenant composite uniqueness target where required, and composite foreign keys for tenant-owned parent references.
- [ ] Implement identity data: global case-insensitive unique user email, password hashes only, user role and disabled timestamp, API-key prefix/hash, and revocation/last-use timestamps.
- [ ] Implement districts with valid PostGIS geography polygons and a GiST index. Exchange geometry with application code as plain values/GeoJSON SQL output rather than R2DBC geometry objects.
- [ ] Implement vehicles with tenant-scoped external reference, chosen `kind` constraint, optional same-tenant home district, latest status copy, and retirement timestamp.
- [ ] Implement telemetry as a Timescale hypertable on event time `ts`, with `(vehicle_id, ts)` primary key, server-assigned per-vehicle `seq`, field range checks, same-tenant vehicle FK, tenant/time index, idempotent insert behavior, and selected retention/chunk policies.
- [ ] Implement `telemetry_1m` and `telemetry_fleet_1m` continuous aggregates with the required battery/speed/count values, real-time reads, and longer retention than raw telemetry.
- [ ] Implement alerts with optional vehicle and district references (at least one required), kind/severity/state constraints, timestamps, JSON details, list index, and active per-vehicle/kind deduplication index. Decide and document whether district-wide deduplication is needed for outage behavior.
- [ ] Implement dashboards and widgets with tenant-scoped ownership, sharing flag, widget configuration and grid coordinates, and cascading widget deletion. Leave overlap validation to the layout library.
- [ ] Apply RLS enabled and forced policies to every tenant-owned table. Policy `USING` and `WITH CHECK` must compare `tenant_id` with the transaction-local `app.tenant_id` setting and fail closed when it is absent.
- [ ] Ensure the runtime role is not table owner, has no `BYPASSRLS`, and receives only required grants. Ensure lookup role/function ownership is constrained and search paths are fixed.
- [ ] Implement exact-email login lookup and API-key-prefix lookup functions that return only required fields. Do not use general tenant-filtered access before tenant identity is established.
- [ ] Deny runtime direct access to aggregates. Expose them only through security-barrier tenant-filtered views using the current tenant setting.
- [ ] Add a schema coverage check that detects any tenant-owned table without enabled/forced RLS and a tenant policy.

### Completion gate

- [ ] Flyway can apply the migration sequence to the intended TimescaleDB/PostGIS image.
- [ ] Two seeded tenants and one user/API key per tenant exist for local development.
- [ ] Schema constraints, RLS, role grants, lookup functions, aggregates, and tenant views match the model before feature code depends on them.

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

- [ ] Bind custom settings in typed configuration classes under `app`: instance ID; JWT secret, issuer and TTL; CORS origins; ingestion queue capacity, batch size and maximum wait; map tile zoom, flush interval and auth timeout; alert battery threshold and offline duration.
- [ ] Configure environment-driven R2DBC runtime credentials (`app_runtime`) and separate Flyway JDBC credentials (`app_owner`).
- [ ] Configure Redis host/port through `SPRING_DATA_REDIS_HOST` and `SPRING_DATA_REDIS_PORT`.
- [ ] Configure server port from `SERVER_PORT`, Prometheus/health exposure from `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`, and keep actuator access internal.
- [ ] Configure dev profile for local Compose, verbose logs and local frontend CORS; prod for strict CORS, minimal logs and internal actuator access.
- [ ] Expose only health and Prometheus actuator endpoints. Configure probes suitable for Compose health checks.
- [ ] Add a single global error mapper that returns standard problem-details JSON for validation and domain errors, and consistent 401/403 behavior.
- [ ] Configure Micrometer/Prometheus base tags including `app.instance-id` so multiple backend instances can be distinguished.
- [ ] Keep SQL migration execution in Flyway; do not also initialize the schema through `schema.sql`, R2DBC schema scripts, or ORM DDL.

### Completion gate

- [ ] Each profile starts with configuration supplied from its environment and no hard-coded secrets.
- [ ] Health and Prometheus endpoints are available only as intended; domain and validation errors have one response format.

## Phase 3: Reactive tenant context, RLS transaction boundary, authentication, and authorization

### Work

- [ ] Implement user login at `POST /api/auth/login` using the `login_lookup` function, a slow salted password hash, and verification off the event loop on bounded elastic.
- [ ] Implement self-issued short-lived JWTs with `sub` user UUID, `tenant_id`, `role`, `iat`, `exp`, and configured issuer. Load the signing key only from environment configuration.
- [ ] Configure reactive Spring Security JWT validation for bearer tokens and role checks.
- [ ] Implement a separate API-key authentication path for `/ingest/v1/**`: look up by prefix, compare the secret hash, reject unknown/revoked/mismatched keys, then resolve the tenant from the key record.
- [ ] Keep login and API-key pre-tenant lookup limited to the database functions; do not broaden unauthenticated database access.
- [ ] Implement security chains/path matchers that distinguish user JWT traffic from machine API-key traffic.
- [ ] Put tenant ID and role in Reactor Context. Never accept tenant identity from body, path, or query parameters, and never use ThreadLocal.
- [ ] Add a shared transaction helper used by repositories/services. Inside each tenant-owned transaction, its first SQL statement sets `app.tenant_id` transaction-locally; never set a pooled connection's session-level tenant.
- [ ] Ensure no other code opens a tenant-owned transaction outside this helper. Ensure pipeline roots without HTTP requests set context from each message before database work.
- [ ] Apply endpoint access rules: viewers can read; operators inherit viewer access and can write, acknowledge/resolve alerts, trigger scenarios, and edit dashboards according to ownership rules.
- [ ] Restrict CORS to configured frontend origins. Add login rate limiting. Do not expose management endpoints publicly.
- [ ] Define a WebSocket authentication timeout and first-message JWT authentication behavior for `/ws/map`.
- [ ] Ensure all credential and token failures are mapped to uniform 401/403 problem responses without leaking hashes or token details.

### Completion gate

- [ ] Login issues and validates the documented claims; disabled users, expired/invalid tokens, wrong roles, unknown/revoked API keys are rejected.
- [ ] A tenant-owned database operation with no context sees no rows and cannot write; each request/transaction sets context on its own transaction.

## Phase 4: Core REST domain and dashboard foundation

### Work

- [ ] Define model enums/value objects from the schema constraints, including status, role, alert state/severity, and selected vehicle/alert kinds.
- [ ] Implement immutable request/response DTOs with validation annotations. Never expose R2DBC entities, password hashes, raw API-key hashes, or internal fields.
- [ ] Implement repository SQL with tenant context for vehicles, districts, alerts, dashboards, widgets, and aggregate reads. Use `DatabaseClient`/SQL for PostGIS expressions and aggregate views where needed.
- [ ] Implement mappers as pure functions only.
- [ ] Implement `GET /api/me` for the authenticated user, role and tenant.
- [ ] Implement vehicle reads: `GET /api/vehicles` with status/district filters and `GET /api/vehicles/{id}` with latest state from Redis, with a defined behavior if cache is absent.
- [ ] Implement district reads and `POST /api/districts`; return boundaries through SQL `ST_AsGeoJSON` and accept validated GeoJSON/coordinates without exposing PostGIS types through R2DBC.
- [ ] Implement alerts list `GET /api/alerts` with status/severity filters, plus `POST /api/alerts/{id}/acknowledge` and `/resolve` with state transition rules.
- [ ] Implement dashboard visibility rules: an owner sees their dashboards; tenant members can see shared dashboards; only owner/operator can edit as defined by the sheet.
- [ ] Implement `GET /api/dashboards`, `GET /api/dashboards/{id}`, `POST /api/dashboards`, `PUT /api/dashboards/{id}` replacing name/share/layout, and `DELETE /api/dashboards/{id}` with widget cascade.
- [ ] Use cursor and `limit` for list routes; timestamps are ISO 8601 UTC; IDs are UUIDs; request bodies are JSON.
- [ ] Use service methods for business rules and orchestration. Controllers only route, validate, read security context and call one service method.

### Completion gate

- [ ] Foundation CRUD works for both seeded tenants, with tenant isolation and viewer/operator/owner behavior enforced.
- [ ] Validation failures and missing/forbidden records use the shared problem-details format.

## Phase 5: Telemetry ingestion and persistence pipeline

### Work

- [ ] Define telemetry request DTOs and enforce batch-size and all field-range constraints before queueing.
- [ ] Implement `POST /ingest/v1/telemetry` authenticated by API key. Resolve tenant only from the authenticated key and reject events referencing vehicles outside that tenant.
- [ ] Maintain/cache the tenant's valid vehicle ID set and define refresh/invalidation behavior when vehicle records change.
- [ ] Assign per-vehicle sequence numbers with an atomic Redis increment. Define Redis key initialization/rebuild behavior so sequence numbers resume from the greatest stored telemetry value after Redis loss.
- [ ] Define Redis key names exactly as tenant-scoped in the data model: latest vehicle hashes, tenant geo index, tile-count hashes, update channel, optional tile channels, and alert channel.
- [ ] Update Redis latest state, geo index and tile counts, then publish vehicle updates. Ensure all keys/channels include tenant ID.
- [ ] Queue accepted events into a bounded shared Reactor sink and return `202 Accepted` only when queued according to the API contract.
- [ ] Document and implement queue-full behavior, Redis failure behavior, database failure behavior, retry policy, duplicate handling and what the accepted response guarantees. Do not acknowledge data whose loss behavior is undefined.
- [ ] Group persistence work by tenant before buffering so each database transaction has one tenant. Use `bufferTimeout` with configured size/time bounds and one batched insert statement per batch.
- [ ] Use conflict-ignore on `(vehicle_id, ts)` for idempotent retries. Retry transient failures with exponential backoff and a bounded strategy; ensure exhausted failures are observable and recoverable rather than silently discarded.
- [ ] Keep the TimescaleDB write as the history path and Redis as latest-state/live path, following the documented ordering only after failure guarantees are explicit.
- [ ] Evaluate alert rules after accepted ingestion; initial rules include low battery and vehicle offline. Open/update alerts and publish alert changes.
- [ ] Start and stop the sink/persistence subscriptions through lifecycle-managed beans. Name/checkpoint/tag the pipelines and limit concurrent Redis/database work.
- [ ] Add ingestion metrics: events received/accepted/rejected per second, queue depth/capacity/drops, batch size, batch write latency, retries and terminal failures.

### Completion gate

- [ ] A valid simulator-style batch is accepted and saved; retrying it does not add duplicate `(vehicle_id, ts)` rows.
- [ ] Invalid batches, cross-tenant vehicle references, API-key errors, queue saturation and dependency failures have explicit observable outcomes.

## Phase 6: Live telemetry reads, metrics, and alert persistence

### Work

- [ ] Implement per-vehicle history `GET /api/vehicles/{id}/history` using `telemetry_1m` tenant-filtered view.
- [ ] Implement fleet chart `GET /api/metrics/fleet` using `telemetry_fleet_1m` tenant-filtered view.
- [ ] Confirm runtime has no direct privileges to aggregates and only reads through tenant-filtered security-barrier views.
- [ ] Persist alerts with valid same-tenant vehicle/district foreign keys and the specified state/timestamp consistency constraints.
- [ ] Implement alert creation/update idempotency and active per-vehicle/kind deduplication. Add district-wide deduplication if outage scenarios require it.
- [ ] Add alert-list metrics/counters if useful, without exposing tenant data in metric labels.
- [ ] Ensure chart/history APIs apply cursor/limit or bounded time ranges to avoid unbounded reads.

### Completion gate

- [ ] Aggregate reads return only the current tenant's metrics and include the newest real-time aggregate interval.
- [ ] Alert state transitions remain consistent in the database and produce events for downstream streaming.

## Phase 7: SSE event stream and replay behavior

### Work

- [ ] Implement `GET /api/stream/events` for authenticated users using a fetch-based browser client contract because native `EventSource` cannot send Authorization headers.
- [ ] Stream `alert.opened`, `alert.updated`, `fleet.metrics`, and periodic `heartbeat` events.
- [ ] Use tenant-scoped Redis pub/sub for alert changes and aggregate polling/updates for metrics; keep all payloads as DTOs.
- [ ] Set alert event IDs to change timestamps. On reconnect, use `Last-Event-ID` to query and replay alerts changed since that timestamp from PostgreSQL.
- [ ] Do not replay metric events; document that the next live metric update resumes the chart.
- [ ] Bound per-client buffering and clean up subscriptions on disconnect. Ensure tenant context is carried into background subscription/query work.
- [ ] Expose SSE connection count and disconnect/error metrics; send heartbeats to keep intermediaries from closing idle streams.

### Completion gate

- [ ] Client reconnect replays missed alert changes, receives future alert/metric updates, and cannot observe another tenant's events.

## Phase 8: Map WebSocket, snapshots, tile subscriptions, and conflation

### Work

- [ ] Implement `/ws/map` as a reactive WebSocket endpoint. First inbound message must be `auth` carrying JWT; enforce configured timeout and close unauthorized/late sockets; send `ready` after validation.
- [ ] Define typed inbound/outbound message records for `auth`, `viewport`, `ready`, `snapshot`, `delta`, `clusters`, and `error`.
- [ ] Require viewport messages to contain bounds, zoom, and `viewportVersion`. Echo that version on every snapshot/delta/cluster response so clients can discard stale viewport responses.
- [ ] Implement slippy-map tile math at a selected fixed subscription zoom. Validate bounds and zoom; handle antimeridian/poles and tile-count limits safely.
- [ ] Maintain an in-memory registry keyed by `(tenant, tile)` to local subscribers. Add/remove tile memberships on viewport changes and clean all memberships on disconnect/error.
- [ ] For newly entered tiles, load snapshot IDs from tenant Redis geo index, then read latest-state hashes and send a snapshot. Avoid sending stale snapshots over newer deltas by merging client-visible updates by sequence number.
- [ ] Use tile count hashes to send `clusters` instead of individual vehicles at low zoom; use individual vehicle states at high zoom.
- [ ] Subscribe each backend instance to tenant-wide Redis updates initially. Map each update to its tile and dispatch only to local subscribers. Keep per-tile Redis channels as a later optimization only if load data justifies them.
- [ ] Give each socket a bounded outbound buffer keyed by vehicle ID. Keep only newest position update per vehicle; count conflated/dropped updates. Preserve delivery behavior for non-position events such as status transitions if those must not be lost.
- [ ] Flush each connection's coalesced updates at the configured fixed interval as batched `delta` messages. Ensure a slow consumer cannot grow memory without bound.
- [ ] Implement Redis listener lifecycle management and Reactor Context propagation for tenant-bearing messages.
- [ ] Add reconnect behavior on the client-facing protocol: after reconnect the client authenticates again and resends its viewport; server returns a fresh snapshot.
- [ ] Add metrics for open sockets, tiles subscribed, snapshot duration, per-connection queue pressure, flush size, and conflated updates.

### Completion gate

- [ ] An authenticated client sees only its tenant's vehicles, receives fresh snapshots on viewport changes, gets deltas for subscribed tiles, ignores prior viewport versions, and remains memory-bounded when slow.

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
