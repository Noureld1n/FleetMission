import { apiRequest } from '@/shared/api/http'
import {
  pageResponseSchema,
  DashboardRequestSchema,
  DashboardResponseSchema,
} from '@/shared/types/contracts'
import { z } from 'zod'
import type { DashboardResponse } from '@/shared/types/contracts'

export type DashboardRequest = {
  name: string
  shared: boolean
  widgets: Array<{
    type: 'map' | 'live_chart' | 'status_tiles' | 'alert_list'
    config: Record<string, unknown>
    gridX: number
    gridY: number
    gridW: number
    gridH: number
  }>
}

export function getDashboards(
  cursor?: string,
): Promise<{ items: DashboardResponse[]; nextCursor: string | null }> {
  const params = new URLSearchParams({ limit: '50' })
  if (cursor) params.set('cursor', cursor)
  return apiRequest(`/dashboards?${params}`, pageResponseSchema(DashboardResponseSchema))
}

export function getDashboard(id: string): Promise<DashboardResponse> {
  return apiRequest(`/dashboards/${encodeURIComponent(id)}`, DashboardResponseSchema)
}

export function createDashboard(request: DashboardRequest): Promise<DashboardResponse> {
  const body = DashboardRequestSchema.parse(request)
  return apiRequest('/dashboards', DashboardResponseSchema, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function updateDashboard(id: string, request: DashboardRequest): Promise<DashboardResponse> {
  const body = DashboardRequestSchema.parse(request)
  return apiRequest(`/dashboards/${encodeURIComponent(id)}`, DashboardResponseSchema, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function deleteDashboard(id: string): Promise<void> {
  return apiRequest(`/dashboards/${encodeURIComponent(id)}`, z.undefined(), {
    method: 'DELETE',
  })
}
