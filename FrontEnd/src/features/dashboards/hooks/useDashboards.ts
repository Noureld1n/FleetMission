import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createDashboard,
  getDashboard,
  getDashboards,
  type DashboardRequest,
} from '@/features/dashboards/api/dashboardApi'
import { queryKeys } from '@/shared/api/queryKeys'

export function useDashboards() {
  return useQuery({ queryKey: queryKeys.dashboards(), queryFn: () => getDashboards() })
}

export function useDashboard(id: string) {
  return useQuery({
    queryKey: queryKeys.dashboard(id),
    queryFn: () => getDashboard(id),
    enabled: Boolean(id),
  })
}

export function useCreateDashboard() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: DashboardRequest) => createDashboard(request),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.dashboards() })
    },
  })
}
