# Frontend source layout

- `app/` owns providers and route configuration.
- `features/<feature>/` owns that feature's API functions, hooks, components, and state.
- `shared/api/` owns the common fetch client and error handling.
- `shared/realtime/` owns framework-independent SSE and WebSocket clients.
- `shared/types/` owns API DTO and realtime message schemas/types.
- `shared/ui/` owns reusable design-system components.
- `shared/lib/` owns small framework-independent helpers.
- `test/` owns shared frontend test setup and fixtures.

Feature code may depend on `shared` and another feature's public `index.ts` only. Shared modules must not import from feature modules. API functions do not import React; presentational components receive data and callbacks through props.
