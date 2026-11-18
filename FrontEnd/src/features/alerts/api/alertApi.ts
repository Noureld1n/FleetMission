import { apiRequest } from '@/shared/api/http'
import { AlertResponseSchema, pageResponseSchema, type AlertResponse } from '@/shared/types/contracts'

export type AlertFilters = { cursor?: string; limit?: number; status?: string; severity?: string }

export function getAlerts(filters: AlertFilters = {}): Promise<{ items: AlertResponse[]; nextCursor: string | null }> {
  const params = new URLSearchParams({ limit: String(filters.limit ?? 6) })
  if (filters.cursor) params.set('cursor', filters.cursor)
  if (filters.status) params.set('status', filters.status)
  if (filters.severity) params.set('severity', filters.severity)
  return apiRequest(`/alerts?${params}`, pageResponseSchema(AlertResponseSchema))
}
