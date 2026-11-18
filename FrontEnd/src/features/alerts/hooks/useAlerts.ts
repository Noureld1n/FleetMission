import { useQuery } from '@tanstack/react-query'
import { getAlerts, type AlertFilters } from '@/features/alerts/api/alertApi'
import { queryKeys } from '@/shared/api/queryKeys'

export function useAlerts(filters: AlertFilters = { limit: 6 }) {
  return useQuery({ queryKey: queryKeys.alerts(filters), queryFn: () => getAlerts(filters) })
}
