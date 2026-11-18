import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createDashboard,
  deleteDashboard,
  getDashboard,
  getDashboards,
  updateDashboard,
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

export function useUpdateDashboard() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, request }: { id: string; request: DashboardRequest }) =>
      updateDashboard(id, request),
    onSuccess: async (dashboard) => {
      queryClient.setQueryData(queryKeys.dashboard(dashboard.id), dashboard)
      await queryClient.invalidateQueries({ queryKey: queryKeys.dashboards() })
    },
  })
}

export function useDeleteDashboard() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => deleteDashboard(id),
    onSuccess: async (_result, id) => {
      queryClient.removeQueries({ queryKey: queryKeys.dashboard(id), exact: true })
      await queryClient.invalidateQueries({ queryKey: queryKeys.dashboards() })
    },
  })
}
