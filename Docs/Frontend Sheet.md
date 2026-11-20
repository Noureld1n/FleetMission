# Fleet Mission Control: Frontend Sheet

Nov 18, 2026 · @Noureldin

## Project setup

Scaffold the frontend with Vite and the React plus TypeScript template. [Vite 8 became stable on 12 March 2026](https://vite.dev/blog/announcing-vite8), uses Rolldown as its single bundler, and requires Node.js 20.19 or newer, or 22.12 or newer. React 19, Tailwind CSS v4, and shadcn/ui complete the base.

### Scaffold steps

```text
npm create vite@latest frontend -- --template react-ts
cd frontend
npm install
npm install tailwindcss @tailwindcss/vite
npx shadcn@latest init
```

| Step | What to do |
| --- | --- |
| Tailwind plugin | Register the `tailwindcss()` plugin from `@tailwindcss/vite` in `vite.config.ts` |
| Tailwind CSS | Put `@import "tailwindcss";` at the top of `src/index.css` |
| Path alias | Map `@` to `src` in `tsconfig` and in Vite (Vite 8 can resolve `tsconfig` paths when `resolve.tsconfigPaths` is true) |
| shadcn/ui | Run the init command above; it detects Vite and Tailwind v4, writes `components.json`, and creates `src/lib/utils.ts` |
| Components | Add them as needed, for example `npx shadcn@latest add button card dialog input` |

### Dependencies

| Package | Role | Notes |
| --- | --- | --- |
| `react`, `react-dom` | UI | React 19 |
| `react-router` | Routing | Library mode with route guards |
| `@tanstack/react-query` | Server state | Caches REST data and handles mutations |
| `zustand` | Live state | Viewport, vehicle positions, connection status, chart buffers |
| `maplibre-gl` | Map | Version 6 is ESM-only; see the notes below |
| `uplot` | High-frequency chart | Used directly from a thin React component |
| `recharts` | Ordinary charts | For the slower, per-minute charts |
| `react-grid-layout` | Dashboard grid | v2 API with the `useContainerWidth` hook |
| `zod` | Runtime validation | Checks API responses and every incoming socket message; recommended, not required |
| shadcn/ui, `lucide-react` | Components and icons | Installed by the shadcn CLI |

For the SSE stream, use a small fetch-based reader written in-house, or a package such as `@microsoft/fetch-event-source`; check the package's maintenance status first. The built-in `EventSource` cannot send an `Authorization` header.

### Dev dependencies

| Package | Role |
| --- | --- |
| `typescript`, `eslint`, `typescript-eslint`, `prettier` | Types, linting, formatting |
| `vitest`, `@testing-library/react`, `jsdom` | Unit and component tests |
| `msw` | Mock REST responses in tests; recommended |
| `@playwright/test` | End-to-end tests of the main flow |

### MapLibre GL JS 6 notes

[MapLibre GL JS 6.0.0 was released on 22 July 2026](https://github.com/maplibre/maplibre-gl-js/releases/tag/v6.0.0) with several breaking changes that affect this project directly:

- **ESM-only.** The default import no longer works. Use `import * as maplibregl from 'maplibre-gl'` or named imports.
- **WebGL2 is required.** If it is unavailable, the map fires an `error` event you can listen for, which the app should turn into a friendly message.
- **`setData` changed.** `GeoJSONSource.setData` no longer takes a second argument and no longer returns the source, so never chain calls onto it. This matters because the live layer calls it constantly.
- **Bundler worker setup.** The ESM build loads its worker as a real URL. Another library's release notes describe passing the worker URL to `setWorkerUrl()` once, before creating the map, when bundling with Vite; confirm the exact step against MapLibre's v5 to v6 migration guide.
- **Skip wrapper libraries** unless they explicitly support version 6. This project drives the map imperatively from a store, so a thin hook around the map object is simpler and faster.

### Leave out

- **Next.js or any server rendering.** The app is a client-side dashboard behind a login.
- **Redux.** TanStack Query plus Zustand already cover both kinds of state.
- **Axios.** `fetch` is enough.
- **Socket.IO.** The backend speaks plain WebSocket, which the Socket.IO client cannot talk to.
- **Leaflet and DOM markers.** They choke at fleet scale; MapLibre renders vehicles on the GPU.
- **A second component library.** shadcn/ui is the only one.

## Structure

The backend is layered by role; the frontend is organized by feature, and each feature repeats the same small set of folders. A reader who knows one feature can find their way around any other.

### Folder layout

```text
src/
├── main.tsx                 app entry
├── app/
│   ├── App.tsx              mounts providers and the router
│   ├── providers.tsx        QueryClient, auth, theme
│   └── router.tsx           route table and guards
├── features/
│   ├── auth/                login form, auth store, route guards
│   ├── map/                 map view, map socket, map store
│   ├── fleet/               vehicle list and detail
│   ├── alerts/              alert list and SSE handling
│   ├── metrics/             fleet and vehicle charts
│   └── dashboards/          dashboard list, editor, widget registry
├── shared/
│   ├── api/                 HTTP client and endpoint functions
│   ├── realtime/            SSE client and WebSocket client
│   ├── ui/                  shadcn components and app primitives
│   ├── lib/                 tile math, formatting, helpers
│   └── types/               shared types and zod schemas
└── test/                    setup, msw handlers, fixtures
```

```text
features/map/
├── components/              MapView, MapControls, ClusterLegend
├── hooks/                   useMapSocket, useViewportSync
├── store/                   mapStore.ts (Zustand)
├── api/                     REST calls specific to this feature
└── index.ts                 the feature's public exports
```

A feature may import from `shared`, and from another feature only through that feature's `index.ts`. Nothing in `shared` imports from a feature.

### How it mirrors the backend

| Backend layer | Frontend counterpart |
| --- | --- |
| Controller | Route components and page-level components in each feature |
| Service | Hooks that combine data, live state, and rules (`useMapSocket`, `useAlerts`) |
| Repository | Functions in `api/` and `shared/realtime/` that talk to the backend |
| Model | Types in `shared/types`, with zod schemas that mirror the backend's response shapes |
| Dto | The same zod schemas, used to validate what arrives over HTTP, SSE, and WebSocket |
| View | Presentational components in `components/` and `shared/ui/` |

## Layer rules

Data flows one way: the backend clients feed stores and caches, hooks read from those, and components render what hooks return. Each layer has a short list of things it does and never does.

| Layer | Does | Never does |
| --- | --- | --- |
| Presentational component | Renders from props and reports user actions through callbacks | Fetches data or reads a store directly |
| Route or page component | Composes a feature's components, reads route params, and calls hooks | Contains business rules or talks to the network |
| Hook | Combines query data, store slices, and rules into what the UI needs | Renders JSX |
| API function | One typed function per endpoint, validating the response with zod | Imports React |
| Realtime client | Owns a connection, reconnects with backoff, validates every message, and exposes subscribe and unsubscribe | Imports React or knows about components |
| Store | Holds live state and the actions that apply incoming messages (such as merging by `seq`) | Fetches data |

### Where each kind of state lives

The split from the stack decision is the core rule: TanStack Query owns what the server owns, and Zustand owns what changes many times a second.

| State | Home | Why |
| --- | --- | --- |
| Dashboards, vehicle lists, alert lists, history series | TanStack Query | Caching, refetching, and mutations come built in |
| Live vehicle positions, keyed by vehicle ID with `seq` | Map store (Zustand) | Updates arrive many times a second, outside the React render path |
| Viewport and `viewportVersion` | Map store (Zustand) | Read by the socket client and the map together |
| Connection status for the WebSocket and SSE | Connection store (Zustand) | Drives the live, reconnecting, or stale indicator |
| Live chart buffers | Mutable arrays owned by the chart component | uPlot takes arrays directly; copying through React state would waste frames |
| Signed-in user and token | Auth store (Zustand), mirrored to session storage | Read by every API call and socket |
| Dashboard layout being edited | Local component state until saved | Saved with one mutation, not on every drag |
| Alerts arriving over SSE | Merged into the Query cache | The alert list and the live stream then share one source of truth |
| Modals, tabs, and form fields | Component state or the URL | Not shared |

### Rules that apply everywhere

- **Keep fast data out of React state and out of the Query cache.** Live positions go into the store, and the map and charts read them on a timer.
- **Select narrowly.** Components subscribe to the smallest store slice they need, so unrelated updates do not re-render them.
- **Validate once, at the boundary.** Zod checks every HTTP response and every socket message as it arrives; inside the app, trust the types.
- **One connection per session.** A single WebSocket and a single SSE stream are owned at app level, never opened by individual components.
- **Always clean up.** Every subscription returns an unsubscribe function, and effects undo what they start. React's development mode runs effects twice on purpose, so a missing cleanup shows up immediately.
- **Treat the map and charts as imperative objects.** They live in refs; React only mounts and unmounts them.
- **Strict TypeScript.** No `any`; unknown input is narrowed by zod, not cast.
- **Contain failures.** An error boundary wraps each feature, and failed queries surface as toasts rather than blank screens.

## Screens and routes

Every screen except login sits inside one app shell: a sidebar for navigation, a top bar with the tenant name, the user menu, and the connection indicator (live, reconnecting, or stale). Roles are cumulative, as in the backend: an operator sees everything a viewer sees, plus the editing controls.

| Route | Screen | Access | What it shows |
| --- | --- | --- | --- |
| `/login` | Login | Public | Email and password form; on success, goes to the dashboards |
| `/dashboards` | Dashboard list | Viewer | Own and shared dashboards; the create button appears for operators |
| `/dashboards/:id` | Dashboard | Viewer to view, operator who owns it to edit | The widget grid, with an edit mode for dragging, resizing, and saving |
| `/map` | Live map | Viewer | A full-screen map with a side panel for filters and the selected vehicle; operators also see the outage button |
| `/vehicles` | Vehicle list | Viewer | Filterable list by status and district |
| `/vehicles/:id` | Vehicle detail | Viewer | Latest state and a history chart from the 1-minute aggregate |
| `/alerts` | Alerts | Viewer to view, operator to act | Alert list with status and severity filters; acknowledge and resolve buttons for operators |
| `*` | Not found | Public | A short message with a link back |

### Dashboard widgets

Each widget row from the data model maps to one component through a small registry, so adding a widget type means one new entry, not changes across the app.

| Widget type | Component | Data source | Configuration |
| --- | --- | --- | --- |
| `map` | Map widget | Map WebSocket | Starting viewport |
| `live_chart` | Streaming chart (uPlot) | History query, then `fleet.metrics` events | Which metric to plot |
| `status_tiles` | Status tiles | Fleet metrics and vehicle counts | Which tiles to show |
| `alert_list` | Alert list | Alerts query, kept fresh by SSE | Status and severity filter |

### Dashboard editing

- A dashboard opens in view mode. An operator who owns it can switch to edit mode, which enables dragging and resizing.
- Layout changes stay in local state. **Save** sends one request with the whole widget list; **Cancel** discards the changes.
- Leaving the page with unsaved changes asks for confirmation.

### Every screen handles four states

Loading shows skeletons, empty shows a helpful message, error shows a retry action inside the feature's error boundary, and a stale connection shows the indicator without hiding the last known data.

### Hiding controls is not security

Viewers never see edit, acknowledge, or outage buttons, but the backend enforces roles on every request, so the interface only reflects permissions and never grants them.

### One gap to close in the API

The status tiles need vehicle counts by status for the whole fleet, and the backend sheet's endpoint list has no route that provides them. The proposal is a `GET /api/fleet/summary` route that returns those counts, which would also feed the fleet-level numbers in the top bar.

## Data and realtime clients

Three clients talk to the backend, and all three live outside React: a REST client, a fetch-based SSE reader, and the map WebSocket client. Hooks wrap them for components.

### REST client

- **One `http` helper** wraps `fetch`: it reads the base URL from the environment, adds the `Authorization` header from the auth store, parses JSON, and turns problem-details errors into a typed `ApiError`.
- **A 401 response** clears the auth store and sends the user to the login route.
- **One function per endpoint**, matching the backend's endpoint table, each validating its response with zod.
- **Query keys** follow a fixed pattern so invalidation stays predictable: `['vehicles', filters]`, `['alerts', filters]`, `['dashboards']`, `['dashboard', id]`, `['metrics', 'fleet', range]`, `['vehicle-history', id, range]`.
- **Mutations** invalidate the keys they affect. Retries are left to TanStack Query and apply to reads only.

### SSE client

It connects to `/api/stream/events` with `fetch`, so the `Authorization` header can be sent, and reads the response as a stream of events.

| Event | What the client does |
| --- | --- |
| `alert.opened`, `alert.updated` | Write the alert into the Query cache with `setQueryData`, so the alert list and any toasts update together |
| `fleet.metrics` | Append the point to the fleet metrics buffer in the store |
| `heartbeat` | Record the time, to prove the stream is alive |

- **Resume.** The client remembers the last alert event ID. On reconnect it sends that value in a `Last-Event-ID` header, and the server replays alerts changed since then.
- **Reconnect** uses exponential backoff with random jitter and a cap.
- **Staleness.** If neither events nor heartbeats arrive within a set time, the client marks the stream stale and reconnects.
- **Auth failure.** A 401 stops reconnecting and sends the user to login.

### Map WebSocket client

The client moves through a small set of states, shown in the connection indicator.

| State | Meaning | Next |
| --- | --- | --- |
| `connecting` | Socket opening | `authenticating` when open |
| `authenticating` | `auth` message sent | `ready` when the server answers `ready` |
| `ready` | Viewport sent, messages flowing | `reconnecting` if the socket drops |
| `reconnecting` | Waiting to retry with backoff and jitter | `connecting` |
| `closed` | Stopped on purpose (logout, or an auth failure) | None |

**Sending the viewport.** Map movement is debounced until it settles. Each send increments `viewportVersion` in the store and carries the bounds and zoom. After every reconnect, the client authenticates again and re-sends the current viewport.

**Receiving messages.** Each message is checked with a zod schema that discriminates on `type`. Messages whose `viewportVersion` is older than the current one are dropped, because they were built for a viewport the user has already left.

| Message | What the client does |
| --- | --- |
| `snapshot` | Merge each vehicle into the store only if its `seq` is higher than the stored one, and remove vehicles that left the viewport |
| `delta` | Same merge rule, applied to the batched updates |
| `clusters` | Replace the cluster list and hide individual vehicles |
| `error` | Show a toast; if it is an auth error, stop and go to login |

**Buffering.** Messages write into the store immediately, which is cheap, and mark the vehicle as changed. A separate flush tick copies only the changed vehicles to the map, as described in the Map and charts section.

### Gaps to close with the backend

The backend sheet's map protocol has no heartbeat, so a silently dead socket cannot be told apart from a quiet fleet. The proposal is a small `ping` and `pong` pair, or a periodic server heartbeat message, so the client can mark the map stale.

## Map and charts

The map and charts are imperative objects fed by stores on a timer. React mounts them once, and everything after that bypasses React's render cycle.

### Map

A `useMap` hook creates the map once from a ref, using the namespace import required by MapLibre 6, and removes it on unmount. The basemap style URL comes from the environment and points to a free vector-tile provider such as OpenFreeMap or the MapTiler free tier.

| Layer | Source | Look |
| --- | --- | --- |
| Vehicles | GeoJSON built from the map store | Circles coloured by status through a data-driven style; the selected vehicle gets a highlight ring |
| Clusters | GeoJSON built from the latest `clusters` message | Circles sized by count, with the count as a label |
| Districts | GeoJSON from `GET /api/districts` | Light fill and outline, turning red while a district outage alert is open |

The server decides whether to send vehicles or clusters for the current zoom. The client simply shows whichever the latest message type calls for, and hides the other layer so the two never overlap.

**The update loop.**

1. Socket messages write into the store and mark it dirty.
2. On each animation frame, if the store is dirty, the loop builds a feature collection from the vehicles and calls `setData` on the vehicles source once. It does not chain on the result, since MapLibre 6 no longer returns one.
3. Hidden tabs pause on their own, because animation frames stop.

A few thousand points is well within what this approach handles. If profiling ever shows the rebuild is the bottleneck, the next steps are feature state or a deck.gl layer; both are stretch goals, not starting points.

**Viewport sync.** On `moveend` and `zoomend`, the hook reads the bounds and zoom into the store, and the socket client sends them after a short debounce. Clicking a vehicle stores its ID, and the side panel loads its detail through TanStack Query.

**One active map.** The backend tracks a single viewport per connection, so only one map may be mounted at a time. A dashboard holds at most one map widget, and the `/map` route and a dashboard's map never show together.

**The demo moment.** When an outage alert opens, the affected district turns red, a toast appears, the status tiles update, and the live chart dips, all from the same event. This is the interaction to polish most.

### Charts

- **Streaming chart (uPlot).** Data lives in two parallel arrays, timestamps and values. It is seeded from the history query, then points are appended as `fleet.metrics` events arrive and old points are trimmed to the visible window. The chart redraws on a timer through `setData`, not once per event. A `ResizeObserver` keeps it sized to its widget, and it is destroyed on unmount. Colours come from the app's CSS variables so dark mode works.
- **Per-vehicle history (Recharts).** Small static series from the history query, where Recharts' convenience beats uPlot's raw speed.
- **Status tiles.** Counts and averages from the fleet summary and the latest metrics point, with number formatting and a subtle change animation.

### Dashboard grid

- Use `react-grid-layout` v2, measure the container with its `useContainerWidth` hook, and import both stylesheets (`react-grid-layout/css/styles.css` and `react-resizable/css/styles.css`).
- The layout is derived from each widget's `grid_x`, `grid_y`, `grid_w`, and `grid_h`, with the widget ID as the key.
- Dragging starts only from the widget header, so dragging inside a map pans the map instead of moving the widget. Check the library's documentation for the exact option that sets the drag handle.
- Dragging and resizing are off in view mode and on in edit mode.
- When a widget resizes, a `ResizeObserver` calls the map's `resize` or the chart's `setSize`.

### Render cadence at a glance

| Stream | Arrives | Rendered |
| --- | --- | --- |
| Vehicle deltas | Many times a second | Once per animation frame, only when dirty |
| `fleet.metrics` | Periodically | Chart redraw on a timer |
| Alerts | Occasionally | Immediately, as a cache update and a toast |
| Viewport changes | When map movement settles | Sent straight away after the debounce |

## Auth and role-based UI

The backend issues a JWT at login and has no refresh endpoint, so the frontend's job is to hold the token carefully, attach it to every request, and react cleanly when it expires.

### Where the token lives

| Option | Survives refresh | Exposure | Verdict |
| --- | --- | --- | --- |
| Memory only | No | Smallest: gone when the tab closes, but still readable by injected script while open | Safest, but every refresh logs the user out |
| Session storage | Yes, per tab | Readable by any script on the page | **Chosen:** a refresh keeps the session, and the token dies with the tab |
| Local storage | Yes, across tabs and restarts | Readable by any script, and lives on after the tab closes | Rejected |
| httpOnly cookie | Yes | Not readable by script | Best, but needs backend support the current design does not include |

The auth store holds the token in memory and mirrors it to session storage. If the backend later adds refresh tokens in httpOnly cookies, only the store changes.

### Flows

- **Login.** Post to the login route, store the token, load the current user from `GET /api/me`, and go to the page the user originally wanted.
- **Startup.** If session storage holds a token whose `exp` claim is still in the future, hydrate the store and confirm with `GET /api/me`. Reading `exp` locally is only a convenience; the server is what actually validates tokens.
- **Expiry.** Any 401 logs the user out with a short "session expired" message. Set the backend's token lifetime long enough to cover a demo session.
- **Logout.** Clear the store, close the WebSocket and SSE connections, and clear the entire Query cache so nothing from one user's tenant can appear for the next. Then go to the login route.

### Route guards and permission checks

| Tool | Purpose |
| --- | --- |
| `RequireAuth` route wrapper | Redirects anonymous visitors to the login route and remembers where they were going |
| `RequireRole` route wrapper | Blocks operator-only routes for viewers with a clear message |
| `Can` component | Hides buttons such as edit, acknowledge, resolve, and trigger outage for viewers |

The role comes from the user returned by `GET /api/me`. These tools only reflect permissions in the interface; the backend still enforces every one.

### Hardening checklist

- Never put the token in a URL, and never log it. The WebSocket sends it in its first message for this reason.
- Never use `dangerouslySetInnerHTML`. React escapes text by default, and map popups use plain text, since vehicle names are data from outside the app.
- Serve the built app with a content security policy that allows connections only to the API, the WebSocket host, and the tile provider. MapLibre 6 loads its worker as a real URL, so `blob:` workers are no longer needed.
- Commit the lockfile and run a dependency audit in CI.
- For the public demo, add a "Try the demo" button that logs in with seeded viewer or operator credentials. Those credentials are public on purpose, so the demo tenant must hold only fake data, and the login route needs the backend's rate limit.

## Configuration and build

Everything in the browser bundle is public, so the frontend's configuration holds addresses and demo settings, never secrets.

### Environment variables

Vite exposes only variables that start with `VITE_`. Commit an `.env.example`, and keep real values in `.env.development` and `.env.production`.

| Variable | Purpose |
| --- | --- |
| `VITE_API_BASE_URL` | REST and SSE base; leave it as a relative path such as `/api` when a proxy serves both |
| `VITE_WS_URL` | Map WebSocket address; when empty, derive it from the page address, using `wss` on HTTPS |
| `VITE_MAP_STYLE_URL` | Basemap style from the vector-tile provider |
| `VITE_DEMO_VIEWER_EMAIL`, `VITE_DEMO_OPERATOR_EMAIL` | Prefill the "Try the demo" buttons on public builds |

### Development proxy

In development, Vite's proxy forwards `/api` and `/ws` to the backend (the WebSocket route needs the proxy's WebSocket option turned on). The browser then sees a single origin, so no cross-origin rules are involved while developing.

### Production serving

Serve the built files and proxy the API from the same host, with a reverse proxy such as Nginx or Caddy in front. That removes cross-origin requests entirely and simplifies the content security policy. If the frontend is hosted separately instead, the backend's allowed-origins setting must name it.

The reverse proxy needs two specific settings, or the live features fail in ways that look like bugs:

- **WebSocket upgrade** headers on `/ws/map`.
- **No response buffering** on the SSE route, so events reach the browser as they are sent rather than in delayed chunks.

### Scripts

| Script | Runs |
| --- | --- |
| `dev` | Vite dev server with the proxy |
| `build` | Type check, then production build into `dist/` |
| `preview` | Serves the production build locally |
| `typecheck` | TypeScript without emitting |
| `lint` | ESLint |
| `test` | Vitest |
| `test:e2e` | Playwright against a running stack |

### Build and packaging

- **Lazy-load routes** so the login page does not pay for MapLibre and the charts. The map route and dashboard route load on demand.
- **Cache headers.** Hashed asset files can be cached for a long time; `index.html` must never be cached.
- **Docker.** A two-stage image: build with Node, then copy `dist/` into the web server image. Add it to the same Compose file as the backend, so one command starts everything.
- **CI.** GitHub Actions installs, type checks, lints, runs the unit tests, and builds on every push; the end-to-end job runs against Compose.
- **Browser support.** The default Vite build targets widely available browsers, and MapLibre 6 needs WebGL2, which the app checks and reports on startup.

## Testing and build order

Each kind of code has one kind of test, and the pieces that are hardest to get right, the realtime clients and the stores, are plain functions that are easy to test without a browser.

### What to test at each layer

| Layer | Test type | What it proves |
| --- | --- | --- |
| Stores | Vitest unit tests | Merging by `seq` ignores stale updates, viewport changes bump `viewportVersion`, and vehicles leaving the viewport are removed |
| Realtime clients | Vitest with a fake WebSocket and fake timers | State transitions, backoff and jitter, re-sending the viewport after reconnect, dropping old-viewport messages, and the Last-Event-ID resume |
| Zod schemas | Vitest unit tests | Valid messages pass, and malformed or unknown messages are rejected without crashing |
| API functions and hooks | Vitest with `msw` | Correct requests, error mapping, 401 handling, and cache updates |
| Components | Testing Library | Role-based visibility (viewer versus operator), the four screen states, and unsaved-changes confirmation |
| Map and charts | Thin tests around the hooks, with MapLibre and uPlot mocked | Layers are created once, `setData` runs at most once per frame, and everything is destroyed on unmount |
| Whole flow | Playwright against the Compose stack | Log in as two tenants, see only each one's data, watch live updates, pan the map, trigger an outage |

Pixel-level map output is not unit tested; the Playwright test checks that markers and cluster counts appear, not how they look.

### Build order and the folders each stage touches

These stages follow the backend's build plan, so each frontend stage can be tested against the backend stage that just shipped.

| Stage | Folders touched | Done when |
| --- | --- | --- |
| 1. Foundation | `app`, `shared/api`, `shared/ui`, `features/auth`, `features/dashboards` | Two seeded tenants can log in, guards work, and each sees only their own dashboards with one static chart |
| 2. Ingestion visible | `features/fleet`, `features/metrics` | The vehicle list and history charts show data the simulator has produced |
| 3. Live map | `shared/realtime`, `features/map` | A browser shows a tenant's vehicles moving live, with the connection indicator |
| 4. Viewport filtering | `features/map`, `shared/lib` (viewport helpers) | Panning changes what arrives, and stale messages are ignored |
| 5. Performance | `features/map`, `features/metrics` | Clusters appear at low zoom, the frame-flush loop keeps panning smooth, and the streaming chart stays responsive |
| 6. Polish and proof | `features/alerts`, `features/dashboards`, Docker and CI files | Alerts and the outage moment work end to end, the Playwright flow passes, and the demo is deployed |
| 7. Stretch | As needed | Replay slider, position interpolation, deck.gl trails, presence |

A stage is finished only when its tests pass and the demo still works end to end. The frontend's first shippable moment is stage 1, and the first impressive one is stage 4.
