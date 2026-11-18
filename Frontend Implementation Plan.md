# Fleet Mission Control Frontend Implementation Plan

## Goal

Build a responsive, real-time fleet operations dashboard that uses the implemented backend APIs, keeps tenant data isolated in the browser session, and feels polished enough for an operations demo. The visual direction is a modern control-room interface: dark graphite/navy surfaces, crisp typography, electric cyan for live activity, amber for attention, and red for incidents. Use color with labels and icons so status is never conveyed by color alone.

The frontend is a client-side React application. It does not connect directly to PostgreSQL or Redis; all data comes through the backend HTTP, SSE, and WebSocket interfaces.

## Requirements

### Product and screens

- Public login at `/login`.
- Authenticated app shell with sidebar navigation, tenant and user identity in the top bar, and a visible live/reconnecting/stale connection indicator.
- Dashboard list at `/dashboards` and dashboard view/editor at `/dashboards/:id`.
- Live fleet map at `/map`, with vehicle selection, filters, districts, clusters, and an operator-only outage action.
- Fleet list at `/vehicles` and vehicle detail/history at `/vehicles/:id`.
- Alert list at `/alerts`, with viewer read access and operator acknowledge/resolve actions.
- Not-found route and route guards for authenticated and operator-only actions.
- Loading, empty, error/retry, and stale-connection states on every data screen.
- Responsive layouts for wide control-room screens, laptops, tablets, and narrow mobile screens.

### Technical requirements from the Frontend Sheet

- Vite with React 19 and strict TypeScript; Tailwind CSS v4 and shadcn/ui are the only component system.
- React Router for routes; TanStack Query for server-owned data; Zustand for auth, connection, viewport, and high-frequency live state.
- MapLibre GL JS 6 for GPU-rendered map layers, uPlot for high-frequency charts, Recharts for ordinary history charts, and React Grid Layout v2 for dashboard layouts.
- Zod schemas validate HTTP responses and every SSE/WebSocket message at the network boundary.
- Use `fetch`; do not add Axios, Redux, Socket.IO, Leaflet, a second component library, or server-side rendering.
- Organize code by feature. Presentational components receive props, hooks compose state and rules, API functions own HTTP calls, realtime clients do not import React, and shared modules do not import features.
- TanStack Query owns REST data. Zustand owns rapidly changing positions, viewport, connection state, and other live state. Chart buffers stay in chart-owned mutable arrays.
- Own one SSE stream and one WebSocket per signed-in app session, not one connection per page or component.
- Store the JWT in memory and session storage as specified in the Frontend Sheet. Clear it and all tenant-scoped client caches on logout or any authenticated 401.
- The frontend does not include secrets. API, WebSocket, and map-style URLs come from Vite environment variables.

### UI/UX direction: modern and cool

- Use a restrained dark operations theme: deep navy/charcoal page background, slightly raised panels, off-white primary text, muted secondary text, cyan live accents, amber warnings, and red critical states.
- Keep the map as the visual anchor. Place compact translucent controls and a district/vehicle side panel over or beside it, with enough contrast for labels and map details.
- Use clear type hierarchy, consistent spacing, tidy alignment, and dense-but-readable tables. Keep decorative gradients to the login page and a few high-level accents; avoid reducing readability with glowing text or glass effects on every panel.
- Show live status with a small icon, text label, and restrained animation. Use brief transitions for state changes and honor `prefers-reduced-motion`.
- Give critical actions, especially triggering an outage, a clear confirmation step, impact context, success feedback, and a visible resulting alert.
- Make focus rings, keyboard navigation, touch targets, contrast, chart labels, map legends, and empty/error messages deliberate parts of the design.
- Use skeletons for initial loading, compact inline retry states for failed sections, helpful empty states, and keep the last known data visible when a live connection becomes stale.

## Backend endpoint mapping

| Frontend feature | Backend interface |
| --- | --- |
| Login and current session | `POST /api/auth/login`, `GET /api/me` |
| Vehicle list and detail | `GET /api/vehicles`, `GET /api/vehicles/{id}` |
| Vehicle history | `GET /api/vehicles/{id}/history` |
| District list/create | `GET /api/districts`, `POST /api/districts` |
| Fleet charts | `GET /api/metrics/fleet`; live `fleet.metrics` SSE events |
| Alerts | `GET /api/alerts`, `POST /api/alerts/{id}/acknowledge`, `POST /api/alerts/{id}/resolve`; SSE `alert.opened` and `alert.updated` |
| Dashboards and widgets | `GET /api/dashboards`, `GET /api/dashboards/{id}`, `POST /api/dashboards`, `PUT /api/dashboards/{id}`, `DELETE /api/dashboards/{id}` |
| Outage demo action | `POST /api/scenarios/outage`; the resulting alert and live vehicle updates arrive through SSE and WebSocket |
| Live events | `GET /api/stream/events` using a fetch-based SSE reader and `Last-Event-ID` on reconnect |
| Live map | WebSocket `/ws/map`; authenticate with the first `{ "type": "auth", "token": "..." }` frame, wait for `ready`, then send viewport messages |

The browser does not call `POST /ingest/v1/telemetry`, `GET /ingest/v1/commands`, or the command acknowledgement route. Those routes are for the simulator and require its API key.

## Phases

## Phase 0: Project scaffold and API contract

### Work

- [x] Create the frontend with the Vite React/TypeScript template. Use Node.js 20.19+ or 22.12+ as specified in the Frontend Sheet.
- [x] Install and configure React Router, TanStack Query, Zustand, MapLibre GL JS, uPlot, Recharts, React Grid Layout v2, Zod, shadcn/ui, Tailwind v4, and Lucide icons. Add the listed test, lint, and formatting development tools.
- [x] Configure the Tailwind Vite plugin, global stylesheet, `@` path alias, shadcn setup, TypeScript strictness, ESLint, and Prettier.
- [x] Add `.env.example` with `VITE_API_BASE_URL`, `VITE_WS_URL`, and `VITE_MAP_STYLE_URL`; never put credentials or server secrets in Vite variables.
- [x] Configure the development proxy for `/api`, `/ingest`, `/actuator`, and `/ws`; enable WebSocket proxy upgrades. Documented unbuffered SSE as a production proxy requirement for the deployment phase.
- [x] Create the feature folder layout from the Frontend Sheet and establish import boundaries before features grow.
- [x] Create Zod schemas and TypeScript types from backend DTOs for auth, page responses, vehicles, districts, alerts, dashboards, metrics, outage responses, SSE payloads, and map messages.
- [x] Document the map protocol precisely: viewport message shape, tile cluster map, vehicle field names, nullable fields, and `viewportVersion` handling.
- [x] Identify and document the fleet-summary API gap. The backend does not currently expose fleet-wide vehicle counts by status; status tiles remain pending that tenant-scoped endpoint and must not count only the first vehicle page.

### Completion gate

- [x] Vite starts, type checking/linting and production bundling succeed, proxy routes target the documented backend ports, environment configuration has safe examples, and shared response/message schemas reflect backend DTOs. Live proxy connectivity and unbuffered SSE still need a running backend and production proxy configuration.

## Phase 1: Visual foundation, app shell, and authentication

### Work

- [x] Build global design tokens for light/dark surfaces, text, status colors, borders, focus rings, shadows, spacing, and typography. Start with the dark control-room theme and keep colors semantic so an alternate theme can be added consistently.
- [x] Build shared navigation, top bar, breadcrumbs/page titles, connection badge, toast system, skeletons, empty states, retry panels, confirmation dialog, and accessible form controls with shadcn/ui.
- [x] Create the login page with a focused layout, clear Fleet Mission Control identity, email/password form, validation feedback, and responsive styling.
- [x] Implement a typed `fetch` helper that adds the JWT, parses JSON/problem-details responses, validates successful bodies with Zod, and maps failures into `ApiError`.
- [x] Implement the auth store, session-storage hydration, `POST /api/auth/login`, `GET /api/me`, logout cleanup, and 401 handling.
- [x] Add reusable route guards for signed-in users and operator-only routes. The backend remains the authority for access control.
- [x] Create the authenticated shell and route table. Add route-level lazy-loading boundaries for map and vehicle detail routes; MapLibre and chart modules will be introduced in those routes in later phases.
- [x] Create TanStack Query and app-level toast/connection UI providers without opening realtime connections yet.

### Completion gate

- [ ] A user can log in against the running backend, refresh without losing the current tab session, see their tenant and role, visit guarded routes, and log out without retaining cached data from the previous session. Runtime auth verification remains outstanding.
- [x] The shell has consistent loading, error, keyboard-focus, and responsive behavior.

## Phase 2: Dashboards, fleet browsing, districts, and history

### Work

- [x] Implement typed API functions for dashboard, vehicle, district, and metrics endpoints, with query keys and response validation.
- [x] Build `/dashboards` with owned/shared dashboard distinction and an operator-only create action.
- [x] Build dashboard read mode and the first widget registry for `map`, `live_chart`, `status_tiles`, and `alert_list`. Use the backend widget config and grid fields as the source of saved layout.
- [x] Build `/vehicles` with status/district filters, cursor pagination, clear active filters, and an empty state.
- [x] Build `/vehicles/:id` with latest state, status, district, and a Recharts history panel using `from`/`to` query parameters.
- [x] Build district selection/display from `GET /api/districts`; add an operator district creation form for `POST /api/districts` with closed polygon rings and map-friendly validation.
- [x] Build fleet metric query hooks and render the slower per-minute history series.
- Add the fleet-summary endpoint to backend integration before showing authoritative counts in status tiles.
- Handle 404, empty latest state, invalid query ranges, paging, and retry without blanking the whole application shell.

### Completion gate

- [ ] Viewers can browse only their tenant's data, operators see the permitted create controls, dashboards load with their widgets, and vehicle history is readable at desktop and narrow widths. Backend runtime authorization and responsive visual review remain to be verified against a running stack.
- Status tiles use a tenant-wide count response rather than an incomplete vehicle page.

## Phase 3: Realtime client foundation and live map

### Work

- Implement one app-level fetch-based SSE client with bearer auth, parsing for `alert.opened`, `alert.updated`, `fleet.metrics`, and `heartbeat`, and Zod validation for each event.
- Resume alert events with `Last-Event-ID`; reconnect with capped exponential backoff and jitter; mark the stream stale if expected events/heartbeats stop; stop retrying after 401.
- Merge alert events into TanStack Query cache and append/upsert fleet metric points in the appropriate live chart buffer.
- Implement the plain WebSocket client with explicit connecting/authenticating/ready/reconnecting/closed states, JWT first-frame auth, timeout handling, backoff/jitter, cleanup, and current-viewport resend after reconnect.
- Define strict discriminated Zod schemas for `ready`, `snapshot`, `delta`, `clusters`, and `error`. Reject malformed messages safely and send auth failure to the login flow.
- Implement a Zustand map store keyed by vehicle ID. Merge snapshots/deltas only when `seq` increases, reject messages for old viewport versions, and track selection and connection state.
- Build MapLibre setup with a namespace import, tested worker configuration for Vite, WebGL2 availability handling, vector basemap, district layer, vehicle layer, and selected-vehicle highlight.
- Build `/map` with legend, map controls, filters, vehicle detail side panel, live status indicator, and operator-only outage action placeholder.
- Debounce map `moveend`/`zoomend` events and send the documented viewport message after the socket is ready.

### Completion gate

- A signed-in user sees their tenant's vehicles move on the map, district boundaries render, selected vehicle details load, and reconnect restores the current viewport without stale sequence regressions.

## Phase 4: Viewport correctness, clusters, and rendering performance

### Work

- Synchronize map bounds, zoom, and monotonically increasing `viewportVersion` through the map store.
- Parse low-zoom `clusters` responses and render count-sized GeoJSON circles with readable labels and a compact legend; hide individual vehicle features while clusters are active.
- On new snapshots, merge by `seq` and remove vehicles that are no longer in the current viewport. Ignore old viewport responses.
- Build GeoJSON from Zustand data outside React render cycles. Update the MapLibre sources at most once per animation frame and avoid DOM markers.
- Pause the frame update loop naturally in hidden tabs; clean up animation frames, map listeners, sources, and socket subscriptions on unmount.
- Keep high-frequency chart buffers in chart-owned arrays and redraw uPlot on a timer, not per event. Upsert repeated metric timestamps rather than duplicating aggregate buckets.
- Profile representative fleet sizes and viewport changes; fix avoidable rerenders with narrow Zustand selectors.
- Show a friendly WebGL2/map-style error without taking down vehicle, alert, or dashboard routes.

### Completion gate

- Panning and zooming changes server subscriptions, stale viewport data is ignored, clusters and vehicles do not overlap, and map/chart updates remain smooth without React rendering on every telemetry event.

## Phase 5: Dashboard editor and complete widget experiences

### Work

- Use React Grid Layout v2 with `useContainerWidth` and both required stylesheets. Map widgets allow map interaction without dragging the dashboard; dragging starts from the widget header.
- Add owner-only edit mode, drag/resize, local unsaved layout state, save via one `PUT /api/dashboards/{id}`, cancel, and leave-page confirmation.
- Implement create and delete flows using the corresponding dashboard endpoints, including confirmation and success/error feedback.
- Complete widget registry components: map, live chart, status tiles, and alert list. Keep view-only mode safe for viewers and edits limited to dashboard owners.
- Enforce the one-active-map rule: `/map` and a dashboard map widget cannot both mount a map WebSocket simultaneously.
- Add resize observers so MapLibre calls `resize` and charts update size when widget dimensions change.
- Ensure dashboard state survives query reloads and only persists after explicit save.

### Completion gate

- An owner can edit and save a dashboard layout; other tenant members can view shared dashboards but cannot edit them; widget resizing does not break maps or charts.

## Phase 6: Alerts, outage interaction, and operational polish

### Work

- Build `/alerts` with status/severity filters and operator acknowledge/resolve actions. Keep the list synchronized with SSE events and REST query cache.
- Add toast/inline feedback for new and updated alerts; link an alert to its district or vehicle where available.
- Complete the outage flow on the map: choose/confirm a district and duration, call `POST /api/scenarios/outage`, show affected vehicle count, and let SSE/WebSocket updates drive alert, map, and status changes.
- Update district styling for active district outage alerts; selected and affected vehicles receive a clear visual treatment and accessible status label.
- Polish the “demo moment”: triggering outage yields an immediate confirmation, live critical alert, district emphasis, vehicle status change, and updated status/metric visuals as new simulator telemetry arrives.
- Verify `fleet.metrics` event use in charts; do not fabricate chart points in the browser to make the demo look active.
- Add reconnection, stale-data, offline map, API error, and session-expiry copy and interactions.

### Completion gate

- A viewer can follow alerts and live changes but cannot mutate data; an operator can acknowledge/resolve alerts and trigger an outage; the visible updates stay tenant-scoped.

## Phase 7: Quality, accessibility, and release

### Work

- Add Vitest unit tests for stores, `seq` merging, viewport versions, Zod schemas, and realtime connection state transitions with fake sockets/timers.
- Add MSW tests for API functions, loading/error/401 handling, and query-cache mutations.
- Add Testing Library component tests for role-based controls, keyboard behavior, screen states, and dashboard unsaved-change confirmation.
- Add Playwright coverage against the running stack for login, two-tenant separation, vehicle/history browsing, live map, pan/zoom, SSE alert updates, and operator outage flow.
- Check responsive behavior, contrast, keyboard-only use, reduced-motion mode, chart labels, and map/WebGL fallback at supported screen sizes.
- Add error boundaries per feature and verify a broken map does not blank alerts or vehicle screens.
- Configure production proxy for REST, WebSocket upgrade, and unbuffered SSE. Add CSP for API/WebSocket/map tile hosts and the MapLibre worker requirements.
- Add `.env.example`, two-stage frontend container integration with the backend stack, and CI tasks for install, typecheck, lint, tests, and production build.
- Confirm hashed assets receive long-lived caching and `index.html` is not cached. Run dependency audit in CI and commit the lockfile.

### Completion gate

- CI passes, the production build serves with working REST/SSE/WebSocket proxying, tenant isolation and role behavior pass end-to-end, and the demo remains useful with failures or stale connections.

## Phase 8: Optional stretch work

- Replay slider for historical fleet movement.
- Position interpolation between telemetry updates.
- Vehicle trail rendering, evaluating deck.gl only if profiling shows a need.
- Presence indicators for multiple operators and saved map views.
- Additional themes or configurable map basemaps after the core dark control-room experience is stable.

## Cross-phase acceptance checklist

- Every backend response and realtime message is validated at its boundary with Zod.
- No browser-facing code contains API keys or server secrets; auth tokens never appear in URLs or logs.
- Tenant-scoped Query caches, map stores, and streams are cleared on logout and 401.
- Viewers can read and operators can perform only the mutations granted by backend authorization.
- Every screen has loading, empty, error/retry, and stale/live states.
- High-frequency data updates the map/chart imperatively without a React render per event.
- The interface looks like a cohesive, modern operations console and remains legible, responsive, keyboard accessible, and calm under alert conditions.
