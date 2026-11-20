# Fleet Mission Control: Backend Sheet

Nov 17, 2026 · @Noureldin

## Project setup

Build the backend on the latest stable Spring Boot 4.x release with Java 21 or 25. As of 17 November 2026, 4.1.x is the current line, and [Spring Boot 4.2.0 is scheduled for 19 November 2026](https://ecorpit.com/spring-boot-4-1-end-of-support-2027/), so pick 4.2.0 if Initializr lists it when you generate the project. Never pick a milestone, release candidate, or snapshot.

### Initializr settings

| Setting | Value |
| --- | --- |
| Project | Maven |
| Language | Java |
| Spring Boot | Latest stable (not RC, M, or SNAPSHOT) |
| Packaging | Jar |
| Java | 21 or 25; Boot 4 requires 17 or later |
| Group / Artifact | `com.fleetcontrol` / `backend` (suggested names) |
| Base package | `com.fleetcontrol.backend` |

### Dependencies to select

Spring Boot 4 split its starters into smaller modules, and each starter has a test companion that Initializr adds for you. Do not add `spring-boot-starter-test` by hand; the [migration guide](https://github.com/Spring-projects/Spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) says the test starters bring it transitively.

| Select in Initializr | Boot 4 starter | Used for |
| --- | --- | --- |
| Spring Reactive Web | `spring-boot-starter-webflux` | REST, SSE, and the map WebSocket |
| Spring Data R2DBC | `spring-boot-starter-data-r2dbc` | Reactive PostgreSQL access |
| PostgreSQL Driver | JDBC driver, plus the R2DBC driver when paired with R2DBC | Runtime and Flyway connections |
| Spring Data Reactive Redis | `spring-boot-starter-data-redis-reactive` | Latest-state cache, geo index, pub/sub |
| Spring Security | `spring-boot-starter-security` | Reactive security and password hashing |
| OAuth2 Resource Server | `spring-boot-starter-security-oauth2-resource-server` | JWT validation (renamed in Boot 4) |
| Validation | `spring-boot-starter-validation` | Request body validation |
| Spring Boot Actuator | `spring-boot-starter-actuator` | Health and metrics endpoints |
| Prometheus | Micrometer Prometheus registry | Metrics for Grafana |
| Flyway Migration | `spring-boot-starter-flyway` | Migrations; Boot 4 requires this starter, not the bare Flyway jar |
| JDBC API | `spring-boot-starter-jdbc` | Flyway runs over JDBC even though the app uses R2DBC |
| Testcontainers | Boot's Testcontainers support | Integration tests against real containers |

After generating, open the build file and confirm the R2DBC PostgreSQL driver is present. If it is missing, add it by hand.

### Add by hand after download

| Dependency | Scope | Why |
| --- | --- | --- |
| BlockHound (`io.projectreactor.tools:blockhound`) | test | Fails the build when blocking code runs on an event-loop thread; check its JVM flag requirement for your JDK |
| Reactor Test (`io.projectreactor:reactor-test`) | test | `StepVerifier` for reactive pipelines, if not already present |
| Flyway PostgreSQL module (`flyway-database-postgresql`) | runtime | Add only if Flyway reports that PostgreSQL is unsupported |
| Testcontainers PostgreSQL module | test | Artifact name depends on the Testcontainers version Boot manages; check the generated build |

### Leave out

- **Spring Web.** In Boot 4 this is `spring-boot-starter-webmvc`, the servlet stack. Having it on the classpath changes how the app starts.
- **The WebSocket entry.** That starter targets the servlet stack; WebFlux already includes reactive WebSocket support.
- **Spring Data JPA and Spring Data JDBC.** They pull the app away from the reactive model.
- **DevTools and Lombok** are optional conveniences, not requirements.

### Boot 4 changes that affect this project

- **Jackson 3 is the default.** Packages move from `com.fasterxml.jackson` to `tools.jackson`, except the annotations, which keep their old package. To replace the auto-configured mapper, define a `JsonMapper` bean rather than an `ObjectMapper`.
- **`@MockBean` and `@SpyBean` are gone.** Use `@MockitoBean` and `@MockitoSpyBean` in tests.
- **Liveness and readiness probes are on by default** under the health endpoint, which suits the Docker Compose health checks.
- **Java 17 is the floor.** Boot 4 works up to Java 27, so 21 or 25 are both safe LTS choices.

### The simulator is a separate project

Generate it with the same Java and Boot versions, selecting Spring Reactive Web and Spring Boot Actuator. Boot 4 moved the reactive HTTP client into its own starter, `spring-boot-starter-webclient`, so add it by hand if Initializr does not offer it. The simulator only calls the ingestion API over HTTP.

## MVC structure

MVC here is the architectural pattern, not the Spring Web MVC framework: controllers still run on WebFlux and return `Mono` and `Flux`. The app is organized in layers, so each package answers one question: what comes in (controller), what the rules are (service and model), where data lives (repository), and what goes out (dto).

| MVC part | Packages | Responsibility |
| --- | --- | --- |
| Controller | `controller` | Receives REST requests, the SSE subscription, and map WebSocket messages; validates input and delegates, with no business logic |
| Model | `model`, `service`, `repository` | Entities and enums, the business rules and reactive pipelines, and the data access behind them |
| View | `dto`, `mapper` | The JSON, SSE events, and WebSocket messages clients receive, and the conversion from model to those shapes |
| Cross-cutting | `config`, `security`, `exception`, `util` | Wiring, JWT and API-key handling, error mapping, and small helpers such as tile math |

### Package layout

```text
com.Controll.ScreenCon
├── BackendApplication
├── config/          security, redis, r2dbc, websocket, jackson, property classes
├── controller/      REST controllers, SSE controller, WebSocket handler
├── service/         business logic and reactive pipelines
├── repository/      R2DBC repositories and Redis access
├── model/           entities, enums, value objects
├── dto/
│   ├── request/     incoming REST bodies
│   ├── response/    outgoing REST bodies
│   └── message/     WebSocket and SSE payloads
├── mapper/          model to dto conversion
├── security/        JWT service, API-key filter, tenant context
├── exception/       domain exceptions and the global error handler
└── util/            tile math, time helpers
```

```text
src/main/resources
├── application.yml
├── application-dev.yml
├── application-prod.yml
└── db/migration/    one Flyway migration per step in the data model

src/test/java/com/fleetcontrol/backend
├── controller/      WebTestClient slice tests
├── service/         StepVerifier tests for pipelines
├── repository/      Testcontainers tests against real databases
├── integration/     end-to-end flows and tenant-isolation tests
└── support/         shared container setup and BlockHound installation
```

If a layer grows large, split it by feature underneath (for example `controller/fleet`), never by splitting the layers apart.

## Layer rules

A call travels controller, then service, then repository, and the answer travels back as a model that the mapper turns into a dto. Each layer has a short list of things it does and things it never does.

| Layer | Does | Never does |
| --- | --- | --- |
| Controller | Maps the route, validates the request dto, reads tenant and role from the security context, calls one service method, returns `Mono` or `Flux` of a response dto | Touches a repository, holds business rules, builds entities, or calls `block()` |
| Service | Enforces business rules, orchestrates repositories, runs the reactive pipelines, opens transactions and sets the tenant | Knows about HTTP types such as `ServerWebExchange`, or formats JSON |
| Repository | Runs queries (derived, `@Query`, or `DatabaseClient` SQL) and Redis commands | Contains business rules, or references dtos |
| Model | Holds entities, enums, and value objects whose fields match the data model | Depends on Spring web classes, or holds behaviour beyond simple invariants |
| Dto | Carries data in and out as immutable records; request dtos carry validation annotations | Exposes entities directly, or leaks internal fields such as password hashes |
| Mapper | Converts model to dto with pure functions | Performs I/O |

### Reactive rules that apply everywhere

- **Never block.** No `block()`, no `Thread.sleep`, no blocking JDBC. BlockHound turns any slip into a failed test.
- **Subscribe only at the edges.** Services return `Mono` and `Flux`; the framework subscribes. The only manual subscriptions are the long-lived pipeline roots (the ingestion sink and the Redis listener), started and stopped by lifecycle beans.
- **No `ThreadLocal`.** Tenant and user travel in the Reactor `Context`, because a reactive chain can hop threads.
- **Offload CPU-heavy work.** Password hashing and any other slow computation runs on a bounded elastic scheduler, never on an event-loop thread.
- **Set the tenant first, in every transaction.** The first statement is the transaction-local tenant setting described in the data model.
- **Bound every buffer.** Use size or time limits, or conflation, so a slow consumer cannot grow memory without limit.
- **Name and checkpoint pipelines.** Small named chains with checkpoints keep reactive stack traces readable.
- **Keep simple things simple.** Ordinary CRUD is a plain `Mono` chain. Save the heavy operators for the two pipelines in the Pipelines section.
- **One error handler.** Domain exceptions map to HTTP status codes in a single global handler, never in controllers.

## Endpoints

This is the proposed API surface. Roles are cumulative: an operator can do everything a viewer can.

**Conventions.** User-facing routes live under `/api`, machine ingestion under `/ingest/v1`. Bodies are JSON, timestamps are ISO 8601 in UTC, IDs are UUIDs, errors use the standard problem-details format, and list endpoints take `limit` and a `cursor` rather than page numbers.

### REST routes

| Method and path | Access | Purpose |
| --- | --- | --- |
| `POST /api/auth/login` | Public | Email and password in, JWT out |
| `GET /api/me` | Viewer | Current user, role, and tenant |
| `GET /api/vehicles` | Viewer | Vehicle list with filters (status, district) |
| `GET /api/vehicles/{id}` | Viewer | One vehicle with its latest state |
| `GET /api/vehicles/{id}/history` | Viewer | Per-vehicle series from the 1-minute aggregate |
| `GET /api/districts` | Viewer | Districts, with boundaries as GeoJSON |
| `POST /api/districts` | Operator | Create a district |
| `GET /api/metrics/fleet` | Viewer | Fleet-level series from the fleet aggregate |
| `GET /api/alerts` | Viewer | Alert list, filterable by status and severity |
| `POST /api/alerts/{id}/acknowledge` | Operator | Move an alert to acknowledged |
| `POST /api/alerts/{id}/resolve` | Operator | Move an alert to resolved |
| `GET /api/dashboards` | Viewer | Own dashboards plus shared ones |
| `GET /api/dashboards/{id}` | Viewer | One dashboard with its widgets |
| `POST /api/dashboards` | Operator | Create a dashboard |
| `PUT /api/dashboards/{id}` | Operator (owner) | Replace name, sharing flag, and widget layout |
| `DELETE /api/dashboards/{id}` | Operator (owner) | Delete a dashboard and its widgets |
| `POST /api/scenarios/outage` | Operator | Trigger the demo outage in a district |
| `POST /ingest/v1/telemetry` | API key | Submit a batch of telemetry; answers `202 Accepted` |
| `GET /ingest/v1/commands` | API key | Simulator polls for scenario commands |
| `POST /ingest/v1/commands/{id}/acknowledge` | API key | Acknowledge a delivered command |
| `GET /actuator/health`, `/actuator/prometheus` | Internal | Health checks and metrics scraping |

The outage button stores a tenant-scoped command for pickup on the simulator's next poll. The request body contains a `districtId` and optional `durationSeconds` (10–3600; defaults to 120). The command payload contains `action: "outage"`, district ID, and duration. Polling leases commands for 30 seconds; unacknowledged commands are retried after lease expiry and expire after ten minutes. The simulator acknowledges with the command ID. Poll and acknowledgement endpoints require the tenant API key, and the simulator communicates only through these public HTTP routes.

Triggering an outage validates the district in the caller's tenant, selects active vehicles with telemetry from the last five minutes whose latest position lies in the district polygon, stores the command and opens/updates district and per-vehicle `district_outage` alerts in one tenant transaction, and sets affected vehicle statuses to `fault`. After commit, the backend publishes alert events to the tenant SSE channel and fault states to the tenant live-map channel. Vehicle history and chart aggregates continue to represent actual telemetry; the scenario does not fabricate telemetry samples.

### SSE stream

`GET /api/stream/events` carries alerts and chart updates in one stream per user.

| Event | Payload | Notes |
| --- | --- | --- |
| `alert.opened` | The alert |  |
| `alert.updated` | The alert after acknowledge or resolve |  |
| `fleet.metrics` | Latest fleet aggregate point | Live chart feed |
| `heartbeat` | Empty | Keeps proxies from closing idle streams |

Each alert event's `id` is its change timestamp. On reconnect, the browser sends `Last-Event-ID` and the server re-sends alerts changed since then from PostgreSQL; metric events are not replayed, since the next one arrives shortly. Browsers' built-in `EventSource` cannot set an `Authorization` header, so the frontend uses a fetch-based SSE client.

### Map WebSocket

`/ws/map` is the only bidirectional channel. Browsers cannot set headers on a WebSocket either, so the first message authenticates, and the server closes the socket if none arrives in time.

| Direction | Message | Payload | Notes |
| --- | --- | --- | --- |
| Client to server | `auth` | JWT | Must be first |
| Client to server | `viewport` | Bounds (west, south, east, north), zoom, and a `viewportVersion` | Sent when panning or zooming settles |
| Server to client | `ready` | Empty | Sent after successful auth |
| Server to client | `snapshot` | Vehicles in newly entered tiles, each with `seq` | Sent once per viewport change |
| Server to client | `delta` | Batched vehicle updates, each with `seq` | Conflated for slow clients |
| Server to client | `clusters` | Tile counts | Sent instead of vehicles at low zoom |
| Server to client | `error` | Code and message |  |

Every `snapshot`, `delta`, and `clusters` message echoes the `viewportVersion` it was built for, so the client can ignore messages that belong to a viewport it has already left.

## Security

There are two ways in: people authenticate with a JWT the backend issues itself, and machines authenticate with a per-tenant API key. Both end in the same place, a tenant ID in the Reactor `Context`, which every database transaction reads first.

### Two authentication paths

|  | Users | Ingestion |
| --- | --- | --- |
| Routes | `/api/**`, `/ws/map` | `/ingest/v1/**` |
| Credential | `Authorization: Bearer <JWT>` (WebSocket: first message) | API key header |
| Lookup | Login calls `login_lookup`, then the backend verifies the password hash | The key's prefix calls `api_key_lookup`, then the backend verifies the key hash |
| Result | Tenant ID and role from verified claims | Tenant ID from the key's row |
| Rejected when | Token invalid or expired, user disabled | Key unknown, revoked, or hash mismatch |

### JWT claims

| Claim | Meaning |
| --- | --- |
| `sub` | User ID |
| `tenant_id` | The user's tenant, the only source of tenant identity for requests |
| `role` | `operator` or `viewer` |
| `iat`, `exp` | Issue and expiry times; keep tokens short-lived |

The tenant is never taken from a request body, path, or query string. A JWT service signs tokens at login, and the resource-server configuration validates them on every request.

### Filter chain, in order

1. **Path matcher** separates the ingestion chain from the user chain, since they use different credentials.
2. **Authentication** validates the JWT or the API key.
3. **Tenant context writer** copies the tenant ID and role into the Reactor `Context` for the rest of the chain.
4. **Authorization** applies role rules from the endpoint table: viewer for reads, operator for writes.
5. **Error mapping** returns uniform problem-details responses for 401 and 403.

### Carrying the tenant through the reactive chain

- Services read the tenant from the `Context`, never from a parameter a caller could forge or forget.
- A single helper wraps every repository call in a transaction whose first statement sets the tenant. Nothing else is allowed to open a transaction.
- Pipeline roots with no request behind them, such as the Redis listener, carry the tenant in each message and write it into the `Context` before doing any work.

### Hardening checklist

- Passwords hashed with a slow, salted algorithm, and verified off the event loop.
- API keys shown once at creation, stored only as a hash, and revocable.
- Signing key supplied through the environment, never committed.
- CORS restricted to the frontend's origin.
- Login attempts rate-limited, since login is the one public route.
- Actuator endpoints exposed only on an internal network, with only health and Prometheus enabled.

## Pipelines

Two flows carry the reactive weight of the backend: ingestion, which turns telemetry into live updates and stored history, and the map channel, which turns live updates into per-viewport messages. Everything else is plain request handling.

### Ingestion pipeline

Steps 1 to 5 run on the request path, and the controller answers `202 Accepted` once the batch is queued. Steps 6 and 7 run in the background.

1. **Validate.** The controller checks batch size and field ranges against the data model's constraints.
2. **Resolve and filter.** The service takes the tenant from the `Context` and rejects events for vehicles the tenant does not own, using a cached set of vehicle IDs.
3. **Sequence.** Each event gets its per-vehicle `seq` from an atomic Redis increment.
4. **Update live state and publish.** The latest-state hash, geo index, and tile counts are updated, then the update goes to the Redis channel.
5. **Queue.** Events enter a bounded shared sink that decouples the request from storage.
6. **Persist in batches.** Events are grouped by tenant, buffered by size or time, and written as one insert per batch with conflict-ignore, retrying with backoff on failure.
7. **Evaluate alert rules.** Rules such as low battery and vehicle offline open or update alerts, which are published to the alerts channel for the SSE stream.

Persistence batches must belong to a single tenant, because row-level security checks one tenant per transaction. With a handful of tenants, grouping by tenant before buffering is simple and sufficient.

### Map channel pipeline

One WebSocket handler serves every connection, with one state object per connection.

1. **Handshake.** Accept the socket, wait for the `auth` message within a timeout, put tenant and role in the `Context`, and send `ready`.
2. **Viewport change.** Convert the bounds to a tile set, compare it with the connection's current tiles, and update the in-memory registry of tile to connections.
3. **Snapshot.** For newly entered tiles, read vehicles from the Redis geo index and latest-state hashes and send a `snapshot`. At low zoom, send `clusters` from the tile counts instead.
4. **Fan-out.** One Redis subscription per backend instance receives updates. A dispatcher looks up each update's tile in the registry and offers it to every subscribed connection's buffer.
5. **Conflate and flush.** Each connection's buffer keeps only the latest update per vehicle and drains on a short fixed tick as one `delta`.
6. **Close.** On disconnect or error, remove the connection from the registry, release its tiles, and update the connection gauge.

### Reactor toolbox

| Need | Tool | Where |
| --- | --- | --- |
| Decouple request from storage with bounded memory | Shared sink with a bounded buffer | Ingestion step 5 |
| Single-tenant batches | `groupBy` plus `bufferTimeout` | Ingestion step 6 |
| Survive transient database errors | `retryWhen` with exponential backoff | Ingestion step 6 |
| Limit parallel Redis and database calls | `flatMap` with a concurrency argument | Ingestion steps 3 to 4 |
| Receive Redis pub/sub | Reactive message listener container | Map step 4 |
| Per-connection outbound stream | A unicast sink per WebSocket session | Map steps 3 to 5 |
| Keep only the latest update per vehicle | A keyed buffer (map by vehicle ID) drained on a timer; this is custom code, not a stock operator | Map step 5 |
| Keep CPU work off event loops | `subscribeOn` or `publishOn` with the bounded elastic scheduler | Password hashing |
| Readable traces and metrics | `checkpoint`, `name`, and `tag` on named pipelines | Everywhere |

### Metrics to expose

Events ingested per second, batch size and write latency, open WebSocket and SSE connections, tiles subscribed, updates dropped by conflation, and snapshot duration. These feed Grafana and the headline number from the k6 load test.

## Configuration

Configuration lives in `application.yml` with `dev` and `prod` profile overrides, and every secret and connection detail comes from environment variables. Tests use Testcontainers and set these values dynamically instead.

### Platform properties

| Property | Environment variable | Purpose |
| --- | --- | --- |
| `spring.r2dbc.url`, `.username`, `.password` | `SPRING_R2DBC_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD` | Runtime database pool, connecting as `app_runtime` |
| `spring.flyway.url`, `.user`, `.password` | `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD` | Migrations over JDBC, connecting as `app_owner` |
| `spring.data.redis.host`, `.port` | `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` | Redis connection |
| `server.port` | `SERVER_PORT` | Lets two instances run side by side |
| `management.endpoints.web.exposure.include` | `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | Set to health and Prometheus only |

The runtime and migration credentials must differ. The runtime role has no ownership and no `BYPASSRLS`, which is what makes row-level security meaningful.

### Application properties

These are custom properties, bound to one typed configuration class under the `app` prefix. Numeric defaults are chosen during implementation and tuned with the load test, so none are fixed here.

| Property | Purpose |
| --- | --- |
| `app.instance-id` | Tags metrics and logs so two instances can be told apart |
| `app.jwt.secret` | Signing key for self-issued tokens (supplied by environment, never committed) |
| `app.jwt.issuer`, `app.jwt.ttl` | Token issuer name and lifetime |
| `app.cors.allowed-origins` | The frontend's origin |
| `app.ingest.queue-capacity` | Size of the bounded ingestion sink |
| `app.ingest.batch-size`, `app.ingest.batch-max-wait` | Persistence batching limits |
| `app.map.tile-zoom` | The fixed subscription zoom for tiles |
| `app.map.flush-interval` | How often each connection's buffer drains into a `delta` |
| `app.map.auth-timeout` | How long a socket may wait before sending `auth` |
| `app.alerts.low-battery-percent` | Threshold for the low-battery rule |
| `app.alerts.offline-after` | Silence duration before a vehicle counts as offline |

### Profiles

| Profile | Behaviour |
| --- | --- |
| `dev` | Local Docker Compose services, verbose logging, relaxed CORS for the dev server |
| `prod` | Strict CORS, minimal logging, actuator on the internal network only |
| Tests | No profile file; Testcontainers supply database and Redis connection values at runtime |

## Testing and build order

Each layer has one kind of test, so a failure points straight at the layer that caused it. BlockHound is installed once in the shared test support package and guards all of them.

### What to test at each layer

| Layer | Test type | What it proves |
| --- | --- | --- |
| Controller | WebFlux slice test with `WebTestClient` | Routes, validation errors, status codes, and role rules, with services mocked via `@MockitoBean` |
| Service | Unit tests with `StepVerifier` | Business rules and pipeline behaviour, including retries, batching, and conflation, using virtual time for timers |
| Repository | Data slice test against Testcontainers | Real SQL against TimescaleDB and PostGIS, and real commands against Redis |
| Security | Integration test | Invalid, expired, and wrong-role tokens are rejected; revoked API keys fail |
| Isolation | Integration test across the API, socket, and database | Tenant A never sees tenant B's data (the full list is in the data model) |
| Whole flow | End-to-end test | Simulator batch in, row stored, update visible on a map socket |

### Build order and the packages each stage touches

| Stage | Packages touched | Migrations | Done when |
| --- | --- | --- | --- |
| 1. Foundation | `config`, `security`, `model`, `repository`, `controller` (auth, dashboards), `exception` | Roles, identity, districts and vehicles, dashboards | Two seeded tenants can log in and each sees only their own dashboards |
| 2. Ingestion | `controller` (ingest), `service` (ingestion pipeline), `repository` (telemetry, Redis) | Telemetry hypertable, aggregates | A simulator batch lands in the hypertable and a retried batch adds no duplicates |
| 3. Live map, tenant-wide | `controller` (WebSocket handler), `dto/message`, `repository` (Redis) | None | A browser shows a tenant's vehicles moving live |
| 4. Viewport filtering | `util` (tile math), `service` (tile registry, snapshots) | None | Panning changes what arrives, and stale messages are ignored |
| 5. Performance | `service` (conflation, clustering) | None | A deliberately slow client causes drops instead of memory growth; low zoom shows clusters |
| 6. Scale and proof | `config`, metrics, Compose files | Alerts | Two instances share updates through Redis, the k6 number is recorded, and the demo is deployed |
| 7. Stretch | As needed | As needed | Per-tile channels, replay slider, alert rules, presence |

A stage is finished only when its tests pass, its metrics appear in Grafana, and the demo still works end to end.
