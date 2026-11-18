import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createDistrict,
  getDistricts,
  getFleetMetrics,
  getVehicle,
  getVehicleHistory,
  getVehicles,
  type DateRange,
  type VehicleFilters,
  type CreateDistrictRequest,
} from '@/features/fleet/api/fleetApi'
import { queryKeys } from '@/shared/api/queryKeys'

export function useVehicles(filters: VehicleFilters) {
  return useQuery({ queryKey: queryKeys.vehicles(filters), queryFn: () => getVehicles(filters) })
}

export function useVehicle(id: string) {
  return useQuery({
    queryKey: queryKeys.vehicle(id),
    queryFn: () => getVehicle(id),
    enabled: Boolean(id),
  })
}

export function useVehicleHistory(id: string, range: DateRange) {
  return useQuery({
    queryKey: queryKeys.vehicleHistory(id, range.from, range.to),
    queryFn: () => getVehicleHistory(id, range),
    enabled: Boolean(id),
  })
}

export function useDistricts() {
  return useQuery({ queryKey: queryKeys.districts(), queryFn: () => getDistricts() })
}

export function useFleetMetrics(range: DateRange) {
  return useQuery({
    queryKey: queryKeys.fleetMetrics(range.from, range.to),
    queryFn: () => getFleetMetrics(range),
  })
}

export function useCreateDistrict() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: CreateDistrictRequest) => createDistrict(request),
    onSuccess: async () => queryClient.invalidateQueries({ queryKey: queryKeys.districts() }),
  })
}
