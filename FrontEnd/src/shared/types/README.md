# Backend message contract notes

These wire shapes follow the DTOs and message records in `BackEnd`. Keep the schemas in `contracts.ts` aligned with those server types. Validate a message as soon as it crosses the network boundary.

## Map WebSocket

The WebSocket route is `/ws/map`. The first frame must be `{ "type": "auth", "token": "<JWT>" }`. Wait for a `ready` server message before sending viewport messages. Authentication is repeated after every reconnect.

Send a viewport as:

```json
{
  "type": "viewport",
  "viewport": {
    "west": -10,
    "south": 20,
    "east": 10,
    "north": 40,
    "zoom": 8,
    "viewportVersion": 1
  }
}
```

Every `snapshot`, `delta`, and `clusters` response includes the `viewportVersion` it belongs to. Ignore responses for versions older than the store's current version. Vehicles use `vehicleId`, `ts`, `seq`, `lat`, `lon`, `headingDeg`, `speedKmh`, `batteryPct`, and `status`; nullable heading and speed fields can be `null`. Merge a vehicle only if its `seq` is newer than the stored state.

At low zoom the server sends `clusters` instead of vehicle states. `clusters` is an object keyed by fixed-zoom tile coordinate strings such as `"1234:567"`; each value is the vehicle count for that tile. Replace the active cluster collection when a new clusters message arrives, and hide the individual vehicle layer while clusters are active.

The server's outbound record includes all protocol fields. Fields not used by a particular message type can be `null`, so the Zod schemas intentionally model those nullable fields.

## Server-Sent Events

`GET /api/stream/events` sends `alert.opened` and `alert.updated` events with an `AlertResponse` JSON body and an ISO timestamp event ID. `fleet.metrics` contains one fleet metric point and deliberately has no event ID. `heartbeat` indicates the connection is alive and may have no data body. Send the last alert event ID in `Last-Event-ID` when reconnecting; retry with bounded exponential backoff and jitter.

## Tenant and status counts

The backend has no fleet-wide vehicle-count summary route yet. Do not infer status totals from one page of `GET /api/vehicles`; keep status tiles pending a tenant-scoped summary contract.
