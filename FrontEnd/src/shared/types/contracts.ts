import { z } from 'zod'

const uuidSchema = z.string().uuid()
const dateTimeSchema = z.string().refine((value) => Number.isFinite(Date.parse(value)), {
  message: 'Expected an ISO-8601 date-time',
})
const recordSchema = z.record(z.string(), z.unknown())

export const LoginRequestSchema = z.object({
  email: z.string().email().max(320),
  password: z.string().min(1).max(1024),
})
export const LoginResponseSchema = z.object({
  accessToken: z.string().min(1),
  tokenType: z.string(),
  expiresIn: z.number().int().nonnegative(),
})
export type LoginRequest = z.infer<typeof LoginRequestSchema>
export type LoginResponse = z.infer<typeof LoginResponseSchema>

export const UserRoleSchema = z.enum(['viewer', 'operator'])
export const MeResponseSchema = z.object({
  id: uuidSchema,
  email: z.string().email(),
  displayName: z.string(),
  role: UserRoleSchema,
  tenantId: uuidSchema,
  tenantName: z.string(),
})
export type UserRole = z.infer<typeof UserRoleSchema>
export type MeResponse = z.infer<typeof MeResponseSchema>

export function pageResponseSchema<T extends z.ZodType>(itemSchema: T) {
  return z.object({
    items: z.array(itemSchema),
    nextCursor: z.string().nullable(),
  })
}
export type PageResponse<T> = { items: T[]; nextCursor: string | null }

export const VehicleStatusSchema = z.enum(['idle', 'active', 'charging', 'offline', 'fault'])
export const VehicleKindSchema = z.enum(['drone', 'scooter', 'ev_charger'])
export const VehicleLiveStateSchema = z.object({
  ts: dateTimeSchema.optional(),
  seq: z.coerce.number().int().nonnegative().optional(),
  lat: z.coerce.number().finite().optional(),
  lon: z.coerce.number().finite().optional(),
  heading: z.coerce.number().finite().nullable().optional(),
  speed: z.coerce.number().finite().nullable().optional(),
  battery: z.coerce.number().int().min(0).max(100).optional(),
  status: VehicleStatusSchema.optional(),
})
export const VehicleResponseSchema = z.object({
  id: uuidSchema,
  externalRef: z.string(),
  kind: VehicleKindSchema,
  homeDistrictId: uuidSchema.nullable(),
  status: VehicleStatusSchema,
  createdAt: dateTimeSchema,
  retiredAt: dateTimeSchema.nullable(),
  latestState: VehicleLiveStateSchema.nullable(),
})
export type VehicleStatus = z.infer<typeof VehicleStatusSchema>
export type VehicleResponse = z.infer<typeof VehicleResponseSchema>

export const PositionSchema = z.tuple([z.number().finite(), z.number().finite()])
export const DistrictBoundarySchema = z.object({
  type: z.literal('Polygon'),
  coordinates: z.array(z.array(z.array(z.number().finite()))),
})
export const DistrictResponseSchema = z.object({
  id: uuidSchema,
  name: z.string(),
  boundary: DistrictBoundarySchema,
  createdAt: dateTimeSchema,
})
export const CreateDistrictRequestSchema = z.object({
  name: z.string().trim().min(1).max(120),
  boundary: z.object({
    type: z.literal('Polygon'),
    coordinates: z.array(
      z
        .array(
          z.object({
            longitude: z.number().min(-180).max(180),
            latitude: z.number().min(-90).max(90),
          }),
        )
        .min(4),
    ),
  }),
})
export type DistrictResponse = z.infer<typeof DistrictResponseSchema>

export const AlertKindSchema = z.enum(['low_battery', 'vehicle_offline', 'district_outage'])
export const AlertSeveritySchema = z.enum(['info', 'warning', 'critical'])
export const AlertStatusSchema = z.enum(['open', 'acknowledged', 'resolved'])
export const AlertResponseSchema = z.object({
  id: uuidSchema,
  vehicleId: uuidSchema.nullable(),
  districtId: uuidSchema.nullable(),
  kind: AlertKindSchema,
  severity: AlertSeveritySchema,
  status: AlertStatusSchema,
  openedAt: dateTimeSchema,
  acknowledgedAt: dateTimeSchema.nullable(),
  resolvedAt: dateTimeSchema.nullable(),
  updatedAt: dateTimeSchema,
  detail: recordSchema,
})
export type AlertResponse = z.infer<typeof AlertResponseSchema>

export const WidgetTypeSchema = z.enum(['map', 'live_chart', 'status_tiles', 'alert_list'])
export const DashboardWidgetSchema = z.object({
  id: uuidSchema,
  type: WidgetTypeSchema,
  config: recordSchema,
  gridX: z.number().int().nonnegative(),
  gridY: z.number().int().nonnegative(),
  gridW: z.number().int().positive(),
  gridH: z.number().int().positive(),
})
export const DashboardResponseSchema = z.object({
  id: uuidSchema,
  ownerId: uuidSchema,
  name: z.string(),
  shared: z.boolean(),
  createdAt: dateTimeSchema,
  updatedAt: dateTimeSchema,
  widgets: z.array(DashboardWidgetSchema),
})
export const DashboardRequestSchema = z.object({
  name: z.string().trim().min(1).max(120),
  shared: z.boolean(),
  widgets: z
    .array(
      z.object({
        type: WidgetTypeSchema,
        config: recordSchema,
        gridX: z.number().int().nonnegative(),
        gridY: z.number().int().nonnegative(),
        gridW: z.number().int().positive(),
        gridH: z.number().int().positive(),
      }),
    )
    .max(100),
})
export type DashboardResponse = z.infer<typeof DashboardResponseSchema>
export type DashboardWidget = z.infer<typeof DashboardWidgetSchema>

export const FleetMetricPointSchema = z.object({
  bucket: dateTimeSchema,
  batteryAverage: z.number().nullable(),
  batteryMinimum: z.number().int().nullable(),
  sampleCount: z.number().int().nonnegative().nullable(),
})
export const FleetMetricsResponseSchema = z.object({
  from: dateTimeSchema,
  to: dateTimeSchema,
  points: z.array(FleetMetricPointSchema),
})
export const VehicleHistoryPointSchema = FleetMetricPointSchema.extend({
  speedAverage: z.number().nullable(),
  speedMaximum: z.number().nullable(),
})
export const VehicleHistoryResponseSchema = z.object({
  vehicleId: uuidSchema,
  from: dateTimeSchema,
  to: dateTimeSchema,
  points: z.array(VehicleHistoryPointSchema),
})
export type FleetMetricPoint = z.infer<typeof FleetMetricPointSchema>
export type FleetMetricsResponse = z.infer<typeof FleetMetricsResponseSchema>
export type VehicleHistoryResponse = z.infer<typeof VehicleHistoryResponseSchema>

export const OutageScenarioRequestSchema = z.object({
  districtId: uuidSchema,
  durationSeconds: z.number().int().min(10).max(3600).optional(),
})
export const OutageScenarioResponseSchema = z.object({
  commandId: uuidSchema,
  districtId: uuidSchema,
  affectedVehicles: z.number().int().nonnegative(),
  alert: AlertResponseSchema,
})
export type OutageScenarioRequest = z.infer<typeof OutageScenarioRequestSchema>
export type OutageScenarioResponse = z.infer<typeof OutageScenarioResponseSchema>

export const TelemetryVehicleSchema = z.object({
  vehicleId: uuidSchema,
  ts: dateTimeSchema,
  seq: z.number().int().nonnegative(),
  lat: z.number().min(-90).max(90),
  lon: z.number().min(-180).max(180),
  headingDeg: z.number().finite().nullable(),
  speedKmh: z.number().finite().nullable(),
  batteryPct: z.number().int().min(0).max(100),
  status: VehicleStatusSchema,
})
export const ScenarioCommandSchema = z.object({
  id: uuidSchema,
  type: z.literal('outage'),
  payload: recordSchema,
  attempt: z.number().int().positive(),
  expiresAt: dateTimeSchema,
  leaseUntil: dateTimeSchema,
})

export const MapViewportSchema = z
  .object({
    west: z.number().finite().min(-180).max(180),
    south: z.number().finite().min(-90).max(90),
    east: z.number().finite().min(-180).max(180),
    north: z.number().finite().min(-90).max(90),
    zoom: z.number().int().min(0).max(22),
    viewportVersion: z.number().int().nonnegative(),
  })
  .refine((viewport) => viewport.south < viewport.north, {
    message: 'Viewport south must be less than north',
  })
export const MapClientMessageSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('auth'), token: z.string().min(1) }),
  z.object({ type: z.literal('viewport'), viewport: MapViewportSchema }),
])
const nullableBase = {
  code: z.string().nullable().optional(),
  message: z.string().nullable().optional(),
  viewportVersion: z.number().int().nonnegative().nullable().optional(),
  vehicles: z.array(TelemetryVehicleSchema).nullable().optional(),
  clusters: z.record(z.string(), z.number().int().nonnegative()).nullable().optional(),
}
export const MapServerMessageSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('ready'), ...nullableBase }),
  z.object({
    type: z.literal('snapshot'),
    ...nullableBase,
    viewportVersion: z.number().int().nonnegative(),
    vehicles: z.array(TelemetryVehicleSchema),
    clusters: z.null(),
  }),
  z.object({
    type: z.literal('delta'),
    ...nullableBase,
    viewportVersion: z.number().int().nonnegative(),
    vehicles: z.array(TelemetryVehicleSchema),
    clusters: z.null(),
  }),
  z.object({
    type: z.literal('clusters'),
    ...nullableBase,
    viewportVersion: z.number().int().nonnegative(),
    vehicles: z.null(),
    clusters: z.record(z.string(), z.number().int().nonnegative()),
  }),
  z.object({ type: z.literal('error'), ...nullableBase, code: z.string(), message: z.string() }),
])
export type MapViewport = z.infer<typeof MapViewportSchema>
export type MapClientMessage = z.infer<typeof MapClientMessageSchema>
export type MapServerMessage = z.infer<typeof MapServerMessageSchema>
export type TelemetryVehicle = z.infer<typeof TelemetryVehicleSchema>

export const AlertSsePayloadSchema = AlertResponseSchema
export const FleetMetricSsePayloadSchema = FleetMetricPointSchema
export const AlertSseMessageSchema = z.object({
  event: z.enum(['alert.opened', 'alert.updated']),
  id: dateTimeSchema,
  data: AlertSsePayloadSchema,
})
export const FleetMetricSseMessageSchema = z.object({
  event: z.literal('fleet.metrics'),
  data: FleetMetricSsePayloadSchema,
})
export const HeartbeatSseMessageSchema = z.object({
  event: z.literal('heartbeat'),
  data: z.string().optional(),
})
export const ProblemDetailsSchema = z
  .object({
    type: z.string().optional(),
    title: z.string().optional(),
    status: z.number().int().optional(),
    detail: z.string().optional(),
    instance: z.string().optional(),
    code: z.string().optional(),
  })
  .passthrough()
export type ProblemDetails = z.infer<typeof ProblemDetailsSchema>
