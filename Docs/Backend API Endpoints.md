# Backend API Endpoints

This reference describes the routes currently implemented in `BackEnd`. Unless stated otherwise, HTTP routes use the backend's configured server port (default `8080`) and JSON request/response bodies.

## Authentication and access

- User routes under `/api/**` require `Authorization: Bearer <JWT>`, except `POST /api/auth/login`.
- `GET` routes under `/api/**` are available to `viewer` and `operator` users. Mutating routes require `operator`; dashboard edits/deletes also enforce ownership.
- Routes under `/ingest/v1/**` require `X-API-Key: <prefix.secret>`. Data and commands are scoped to the tenant identified by that key.
- `/ws/map` authenticates using a JWT in the first WebSocket message.
- Validation and application errors use the shared problem-details response format.

## User authentication

| Method and path | Access | Use |
| --- | --- | --- |
| `POST /api/auth/login` | Public (rate limited) | Checks email and password and returns a JWT with the authenticated user's tenant and role. JSON body: `email`, `password`. |

## Fleet and dashboard API

| Method and path | Access | Use and query parameters |
| --- | --- | --- |
| `GET /api/me` | Viewer, operator | Returns the current user's identity, role, and tenant. |
| `GET /api/vehicles` | Viewer, operator | Lists tenant vehicles. Optional filters: `status`, `district` (district UUID), `cursor`; `limit` defaults to 50 and is capped at 100. |
| `GET /api/vehicles/{id}` | Viewer, operator | Returns one tenant vehicle and its latest Redis live state, if available. |
| `GET /api/vehicles/{id}/history` | Viewer, operator | Returns the vehicle's aggregated history. Optional ISO-8601 `from` and `to`; defaults to the latest 24 hours and is limited to seven days. |
| `GET /api/districts` | Viewer, operator | Lists tenant districts and their polygon boundaries. Optional `cursor`; `limit` defaults to 50 and is capped at 100. |
| `POST /api/districts` | Operator | Creates a tenant district. Body includes `name` and a Polygon `boundary` with `type` and closed coordinate rings. Returns `201 Created`. |
| `GET /api/metrics/fleet` | Viewer, operator | Returns fleet-level aggregated metrics. Optional ISO-8601 `from` and `to`; defaults to the latest 24 hours and is limited to seven days. |
| `GET /api/alerts` | Viewer, operator | Lists tenant alerts. Optional filters: `status`, `severity`, `cursor`; `limit` defaults to 50 and is capped at 100. |
| `POST /api/alerts/{id}/acknowledge` | Operator | Moves an open alert to acknowledged and returns the updated alert. |
| `POST /api/alerts/{id}/resolve` | Operator | Resolves an open or acknowledged alert and returns the updated alert. |
| `GET /api/dashboards` | Viewer, operator | Lists dashboards visible to the current user: owned dashboards and shared dashboards. Optional `cursor`; `limit` defaults to 50 and is capped at 100. |
| `GET /api/dashboards/{id}` | Viewer, operator | Returns a visible dashboard and its widgets. |
| `POST /api/dashboards` | Operator | Creates a dashboard and its widget layout. Body includes `name`, `shared`, and `widgets`. Returns `201 Created`. |
| `PUT /api/dashboards/{id}` | Operator (owner) | Replaces the dashboard name, sharing flag, and widget layout. |
| `DELETE /api/dashboards/{id}` | Operator (owner) | Deletes the dashboard and its widgets. Returns `204 No Content`. |
| `POST /api/scenarios/outage` | Operator | Starts an outage in a tenant district, updates affected vehicle statuses and alerts, and queues a command for the simulator. Body: `districtId` and optional `durationSeconds` (10–3600, default 120). Returns `201 Created`. |

## Live event streams

| Method and path | Access | Use |
| --- | --- | --- |
| `GET /api/stream/events` | Viewer, operator | Opens a Server-Sent Events stream for alert changes and dashboard metrics. Optional `Last-Event-ID` resumes alert replay from the supplied event timestamp. |
| `GET /ws/map` (WebSocket upgrade) | JWT in first message | Provides tenant-scoped map snapshots and live vehicle updates. Send `{"type":"auth","token":"<JWT>"}` first, then wait for `ready`. Send viewport updates as `{"type":"viewport","viewport":{"west":-10,"south":20,"east":10,"north":40,"zoom":8,"viewportVersion":1}}`. Server messages include `snapshot`, `delta`, `clusters`, and `error`; snapshot/delta/cluster messages echo `viewportVersion`. Authentication must arrive within the configured map auth timeout. |

## Simulator ingestion and commands

| Method and path | Access | Use |
| --- | --- | --- |
| `POST /ingest/v1/telemetry` | Tenant API key | Submits a batch of telemetry events. Successful queueing returns `202 Accepted`; each event includes `vehicleId`, `ts`, coordinates, optional heading/speed, battery percentage, and vehicle status. |
| `GET /ingest/v1/commands` | Tenant API key | Leases pending simulator commands. Optional `limit` defaults to 20 and must be 1–50. Commands are retried after the 30-second lease expires and expire after ten minutes. |
| `POST /ingest/v1/commands/{id}/acknowledge` | Tenant API key | Acknowledges a command. Repeating acknowledgement for an already acknowledged command succeeds; unknown or expired commands return not found. Success returns `204 No Content`. |

## Operations endpoints

The management server defaults to port `8081` and binds to `127.0.0.1` by default.

| Method and path | Access | Use |
| --- | --- | --- |
| `GET /actuator/health` | Public | Returns application health without detailed component information. |
| `GET /actuator/prometheus` | Public | Exposes Prometheus metrics. |

Other Actuator routes are denied by the security configuration. Exposure can also be changed through the management endpoint configuration.
