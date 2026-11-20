# Fleet Mission Control: Design Report

Nov 17, 2026 · @Noureldin

## Overview

Fleet Mission Control is a live operations dashboard for a simulated fleet (delivery drones, scooters, or EV chargers), built as a portfolio piece that shows backend depth, frontend polish, and engineering judgment in a ten-second demo.

Operators watch vehicles move on a live map, with status tiles, battery charts, and a stream of alerts beside it. A simulator service we write produces the data, so the demo is always alive, and a "trigger outage" button can knock out a district and send alerts cascading across the dashboard.

The project has three goals:

- **Look finished.** A deployed demo with a seeded account, a short GIF in the README, and a one-command Docker Compose setup.
- **Make the hard decisions visible.** Multi-tenant isolation, a fully reactive pipeline, and viewport-filtered map streaming are the deep features, each with a written trade-off.
- **Stay shippable.** Build in vertical slices so something demoable exists at every stage, and treat the hardest features as stretch rather than blockers.

## Agreed decisions

These are the choices settled in discussion; the rest of the report builds on them.

| Topic | Decision | Reason |
| --- | --- | --- |
| Domain | Simulated fleet with a live map | Visual, demoable in seconds, and we control the data |
| Backend model | Spring Boot WebFlux, reactive end to end | Thousands of long-lived connections, each with its own filter and slow-consumer risk, suit Reactor; also a learning goal |
| Data access | R2DBC for PostgreSQL | Keeps the whole pipeline non-blocking |
| Map transport | WebSocket, for the map only | The client must send viewport changes while panning |
| Everything else live | SSE for alerts and chart updates | One-way push; auto-reconnect and `Last-Event-ID` come free |
| Map filtering | Viewport filtering, tile-based | The standout feature, accepted as extra work |
| Auth | Spring Security with self-issued JWTs (`tenant_id`, `role` claims) | Keycloak adds operational weight with no portfolio gain |
| Tenant isolation | PostgreSQL row-level security | Strong, testable talking point |
| Database | PostgreSQL + TimescaleDB + PostGIS | One database for relational, time-series, and spatial data |
| Cache and broker | Redis | Pub/sub fan-out, latest-state cache, geo lookups |
| Message bus | No Kafka; Redis Streams only if needed | Unnecessary at portfolio scale |
| Frontend | React, TypeScript, Vite, Tailwind, shadcn/ui, TanStack Query, Zustand | Typed, fast to build, clean server/ephemeral state split |
| Map and charts | MapLibre GL JS; Recharts, with uPlot for the high-frequency chart | GPU rendering and streaming performance |
| Delivery | Docker Compose on a small VPS | Timescale and PostGIS need real extension support |

## Tech stack

The stack is reactive on the backend and map-centric on the frontend, with PostgreSQL carrying relational, time-series, and spatial data in one place.

### Backend

| Layer | Choice | Why |
| --- | --- | --- |
| Runtime | Java 21 or newer (LTS) | Current LTS with a modern language level |
| Framework | Spring Boot (latest stable), WebFlux | Reactor's backpressure and operators fit streaming and per-connection filtering |
| Data access | R2DBC (r2dbc-postgresql) | Non-blocking database calls; SQL written by hand where convenient |
| Migrations | Flyway over plain JDBC at startup | Hypertables, RLS policies, and continuous aggregates are all migrations |
| Redis client | Reactive Lettuce | Most mature part of the reactive ecosystem |
| Security | Reactive Spring Security, JWT resource server | Same claims model without an external identity provider |
| Observability | Micrometer, Prometheus, Grafana | Real numbers: events per second, open sockets, write latency |
| Blocking guard | BlockHound in tests | Any accidental blocking call on the event loop fails the build |

### Data

- **PostgreSQL with TimescaleDB** for telemetry hypertables and continuous aggregates, plus ordinary tables for tenants, users, dashboards, and layouts.
- **PostGIS** for geofences and spatial queries such as "which vehicles are inside the outage district?". Because R2DBC has no first-class geometry support, spatial work stays in SQL and exchanges plain numbers (lat/lon, bounding-box parameters) with the application.
- **Redis** does three jobs: pub/sub fan-out across backend instances, a latest-state cache holding the last known position and status per vehicle, and geo lookups for viewport snapshots.

### Frontend

| Concern | Choice | Why |
| --- | --- | --- |
| Core | React, TypeScript, Vite | Strict typing for widget configs and incoming message shapes |
| Styling | Tailwind CSS, shadcn/ui | Clean SaaS layouts without CSS specificity fights |
| Server state | TanStack Query | REST caching and mutations |
| Live state | Zustand | Ephemeral state: live buffers, viewport, connection status |
| Map | MapLibre GL JS with free vector tiles | GPU-rendered, handles thousands of moving markers; deck.gl optional for trails and heatmaps |
| Charts | Recharts, uPlot for the streaming chart | uPlot is far faster for high-frequency time series |
| Dashboard layout | react-grid-layout | Draggable, resizable widgets saved per user |

### Infrastructure

- **Docker Compose** for the whole system: two backend instances, simulator, PostgreSQL/Timescale/PostGIS, Redis, Grafana, and the frontend.
- **A small VPS** for hosting, because managed free tiers often restrict database extensions; the frontend can sit on a static host.
- **GitHub Actions** for build and test, and **k6** for the load test.

## Ingestion and simulator

Telemetry enters through a real, public ingestion API, and the simulator is just its first client. That keeps the API honest and gives the project a genuine SaaS flavour.

- **Simulator.** A separate small Spring Boot app (or module) runs N vehicles along routes and posts batched telemetry with a per-tenant API key. It never touches the database directly. It also exposes scenario controls such as "trigger outage" for the demo.
- **Ingestion endpoint.** Authenticates the API key, resolves the tenant, validates the batch, and pushes events into a Reactor sink.
- **Batching.** The pipeline groups events with `bufferTimeout` (by size or time) and writes each batch to TimescaleDB in one statement. Row-by-row writes are the classic bottleneck, so this is a deliberate talking point.
- **Fan-out after write path.** Each accepted event also updates the Redis latest-state cache and is published for live delivery to map subscribers and alert rules.
- **Durability option.** Redis Streams can sit between ingestion and storage if a durable buffer is wanted. It is optional, and Kafka is not needed.

Events carry a per-vehicle sequence number from the start. The map channel depends on it to discard stale updates (see the next section).

## Map channel and viewport filtering

The map is the centrepiece, so it gets its own WebSocket channel: the client sends its viewport as it pans and zooms, and the server streams back only what that viewport needs. Alerts and chart updates stay on SSE.

1. **Subscribe to tiles, not rectangles.** The client sends its viewport, the server converts it to a set of tiles (slippy-map tiles, geohash, or H3), and the connection subscribes to those. The server keeps a registry of `(tenant, tile) → subscribers`. A vehicle update is mapped to its tile and delivered only to that tile's subscribers. Checking every connection's rectangle against every event would cost O(connections) per event.
2. **Snapshot, then deltas, with versioning.** When the viewport changes, the server sends the current state of vehicles in the new tiles (from Redis geo), then streams deltas. A delta can race the snapshot, so each vehicle carries a monotonically increasing sequence number and the client keeps only the newest per vehicle.
3. **Level of detail by zoom.** Zoomed out over a whole city, send cluster counts per tile; zoomed in, send individual vehicles. The switch happens automatically.
4. **Per-connection backpressure with conflation.** For a slow client, do not queue every update. Keep only the latest position per vehicle for that connection and drop stale ones. Reactor's operators make this natural, and it is the strongest interview story in the project.
5. **Multi-instance fan-out.** Updates are published through Redis so a vehicle update ingested on one backend instance reaches subscribers connected to another. Start with a tenant-wide channel and filter locally, then optimise to per-tenant, per-tile channels where each instance subscribes only to tiles it has local subscribers for.
6. **Tenant isolation everywhere.** Tile keys, Redis channels, and cache keys all include the tenant ID, and an explicit test proves one tenant can never see another's vehicles.

## Multi-tenancy, security, and RBAC

Tenant isolation is enforced in the database itself, so a bug in application code cannot leak another company's fleet.

- **Row-level security.** Every tenant-owned table has an RLS policy keyed on the tenant ID. With R2DBC, the tenant ID must be set on the same connection that runs the query (per request or per transaction), which is one of the details to get right early.
- **Authentication.** Spring Security issues and validates its own JWTs carrying `tenant_id` and `role` claims. Keycloak is deliberately out of scope.
- **Roles.** Start with two: operator (can trigger scenarios and edit dashboards) and viewer (read only). Add more only if a feature needs them.
- **Machine access.** Ingestion uses per-tenant API keys, stored hashed, separate from user JWTs.
- **Isolation beyond SQL.** Redis keys, pub/sub channels, tile registries, and WebSocket subscriptions are all namespaced by tenant.
- **Proof.** A Testcontainers integration test that tenant A cannot read tenant B's data through the API, the map channel, and direct queries is part of the definition of done.

## Frontend design

The frontend has one job beyond looking good: stay smooth while data arrives faster than a browser can render it.

- **State split.** TanStack Query owns server state (dashboards, users, history queries). Zustand owns ephemeral live state: the current viewport, vehicle positions by ID, connection status, and live chart buffers.
- **Client-side batching.** Incoming WebSocket and SSE messages are buffered and flushed to the map and charts on a fixed tick or `requestAnimationFrame`, never one render per event.
- **Map.** MapLibre GL JS renders vehicles as a GPU layer rather than DOM markers. On pan or zoom end the client sends the new viewport and zoom, applies the snapshot, then merges deltas by sequence number. Clusters appear at low zoom and individual vehicles at high zoom.
- **Charts.** Recharts for ordinary charts, and uPlot for the one high-frequency streaming chart, with the reason for the choice written in the README.
- **Dashboard layout.** react-grid-layout gives draggable, resizable widgets, with the layout saved per user.
- **Resilience.** The map socket reconnects with backoff and re-sends its viewport; SSE streams recover through `Last-Event-ID`. A visible connection indicator shows live, reconnecting, or stale.
- **The memorable interaction.** The "trigger outage" button, with alerts cascading across map tiles, status tiles, and charts at once. This is the demo moment to polish most.

## Testing, observability, and delivery

This is the part recruiters can verify without reading the code, so it carries real weight.

- **Integration tests.** Testcontainers against real TimescaleDB/PostGIS and Redis, including the tenant-isolation test described above.
- **Reactive correctness.** BlockHound in the test suite, plus Reactor's debug and checkpoint tooling and small, named pipelines to keep stack traces readable.
- **Frontend tests.** Vitest for logic such as sequence-number merging and batching, and one or two Playwright end-to-end tests of the main flow.
- **Observability.** Micrometer metrics scraped by Prometheus and shown in Grafana: ingestion rate, batch write latency, open WebSocket and SSE connections, and dropped (conflated) updates.
- **Load test.** A k6 scenario that produces one headline sentence, for example events per second handled with a given number of connected map clients. The number is measured, not guessed.
- **CI and packaging.** GitHub Actions for build and test; Docker Compose to run everything with one command, with two backend instances so the multi-instance fan-out is visible.
- **Presentation.** A public demo link with a seeded account, a GIF or short video at the top of the README, an architecture diagram, and a "decisions and trade-offs" section covering WebFlux, WebSocket versus SSE, tiles, and conflation.

## Build plan

Build in vertical slices, so every stage ends with something deployable and demoable. If time runs out at stage 3, the project is still strong.

1. **Foundation.** Auth, tenants, RLS, dashboard CRUD, and one static chart, deployed from day one.
2. **Ingestion.** The simulator, the API-key-protected ingestion endpoint, and reactive batched writes into TimescaleDB.
3. **Live map, tenant-wide.** The WebSocket map channel streaming all of a tenant's vehicles, with initial state loaded from the Redis latest-state cache.
4. **Viewport filtering.** Tile subscriptions, snapshot-then-deltas, and sequence-number versioning.
5. **Performance features.** Conflation for slow clients and zoom-based clustering.
6. **Scale and proof.** Two backend instances with Redis fan-out, the k6 load test, Grafana dashboards, and the public deployment.
7. **Stretch.** Per-tile Redis subscriptions, a replay slider that scrubs back through recent history, alert rules, and presence ("3 people viewing").

Stages 4 and 5 are where the project becomes impressive, and stage 3 is the safety net underneath them.

## Risks, costs, and trade-offs

The reactive choice and viewport filtering are the two biggest sources of difficulty, and both were accepted knowingly.

| Risk or cost | Mitigation |
| --- | --- |
| Reactive code is harder to debug; stack traces are poor and a wrong operator can fail silently | Small named pipelines, Reactor debug and checkpoints, BlockHound in tests |
| R2DBC has fewer conveniences than JDBC and thinner tooling | Write SQL by hand; keep Flyway on JDBC; use reactive style only where it pays off, and plain code for simple CRUD |
| PostGIS types do not map cleanly through R2DBC | Exchange plain numbers and keep geometry inside SQL; use Redis geo for live lookups |
| Race between snapshot and deltas shows stale positions | Per-vehicle sequence numbers; client keeps only the newest |
| Tenant leakage through a cache, channel, or tile registry | Tenant ID in every key; explicit isolation tests across API, socket, and database |
| Per-tile Redis fan-out is more complex than a tenant-wide channel | Start tenant-wide with local filtering; optimise only after the load test shows a need |
| Scope creep | Staged build plan; anything past stage 6 is optional |

**Deliberately left out:** CRDT-based collaborative editing, a custom query builder, a complex alerting engine, Keycloak, and Kafka. Each looks impressive but costs far more than it adds to a portfolio, and a polished narrower project beats an ambitious half-working one.

## Next step: the data model

The data model comes next, designed in words first and then implemented as Flyway migrations. It needs to answer these questions:

- **Tenancy and identity.** Tenants, users, roles, and API keys, and how the tenant ID is carried on every tenant-owned table so RLS policies stay uniform.
- **Fleet entities.** Vehicles, vehicle types, geofences or districts, and the fields a vehicle's latest state needs (position, status, battery, sequence number).
- **Telemetry.** The hypertable shape, chunking and retention choices, and the continuous aggregates that feed charts without scanning raw rows.
- **Alerts.** How alerts are recorded and tied to vehicles, districts, and tenants.
- **Dashboards.** Dashboards, widgets, and per-user layouts.
- **Redis layout.** Key and channel naming for the latest-state cache, geo index, and pub/sub, all tenant-scoped, plus the tile scheme (slippy tiles, geohash, or H3) which needs to be chosen before the map channel is built.

One open decision to settle first: which tile scheme to use, because it shapes both the Redis layout and the viewport protocol.
