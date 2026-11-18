import { apiRequest } from '@/shared/api/http'
import {
  CreateDistrictRequestSchema,
  DistrictResponseSchema,
  FleetMetricsResponseSchema,
  VehicleHistoryResponseSchema,
  VehicleResponseSchema,
  VehicleStatusSchema,
  pageResponseSchema,
  type DistrictResponse,
  type FleetMetricsResponse,
  type VehicleHistoryResponse,
  type VehicleResponse,
  type VehicleStatus,
} from '@/shared/types/contracts'
export type { VehicleStatus } from '@/shared/types/contracts'

export type VehicleFilters = { status?: VehicleStatus; district?: string; cursor?: string }
export type DateRange = { from: string; to: string }

export function getVehicles(
  filters: VehicleFilters = {},
): Promise<{ items: VehicleResponse[]; nextCursor: string | null }> {
  const params = new URLSearchParams({ limit: '50' })
  if (filters.status) params.set('status', filters.status)
  if (filters.district) params.set('district', filters.district)
  if (filters.cursor) params.set('cursor', filters.cursor)
  return apiRequest(`/vehicles?${params}`, pageResponseSchema(VehicleResponseSchema))
}

export function getVehicle(id: string): Promise<VehicleResponse> {
  return apiRequest(`/vehicles/${encodeURIComponent(id)}`, VehicleResponseSchema)
}

export function getVehicleHistory(id: string, range: DateRange): Promise<VehicleHistoryResponse> {
  const params = new URLSearchParams({ from: range.from, to: range.to })
  return apiRequest(
    `/vehicles/${encodeURIComponent(id)}/history?${params}`,
    VehicleHistoryResponseSchema,
  )
}

export function getDistricts(
  cursor?: string,
): Promise<{ items: DistrictResponse[]; nextCursor: string | null }> {
  const params = new URLSearchParams({ limit: '100' })
  if (cursor) params.set('cursor', cursor)
  return apiRequest(`/districts?${params}`, pageResponseSchema(DistrictResponseSchema))
}

export type CreateDistrictRequest = {
  name: string
  boundary: {
    type: 'Polygon'
    coordinates: Array<Array<{ longitude: number; latitude: number }>>
  }
}

export function createDistrict(request: CreateDistrictRequest): Promise<DistrictResponse> {
  const body = CreateDistrictRequestSchema.parse(request)
  return apiRequest('/districts', DistrictResponseSchema, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function getFleetMetrics(range: DateRange): Promise<FleetMetricsResponse> {
  const params = new URLSearchParams({ from: range.from, to: range.to })
  return apiRequest(`/metrics/fleet?${params}`, FleetMetricsResponseSchema)
}

export const vehicleStatuses = VehicleStatusSchema.options
