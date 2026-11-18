import { create } from 'zustand'
import type { MapServerMessage, MapViewport, TelemetryVehicle } from '@/shared/types/contracts'

type MapState = {
  vehicles: Record<string, TelemetryVehicle>
  clusters: Record<string, number>
  viewport: Omit<MapViewport, 'viewportVersion'> | null
  viewportVersion: number
  selectedVehicleId: string | null
  dataMode: 'vehicles' | 'clusters'
  lastError: string | null
  setViewport: (viewport: Omit<MapViewport, 'viewportVersion'>) => MapViewport
  setSelectedVehicle: (id: string | null) => void
  setLastError: (message: string | null) => void
  applyMessage: (message: MapServerMessage) => boolean
  clearMap: () => void
}

export const useMapStore = create<MapState>((set, get) => ({
  vehicles: {},
  clusters: {},
  viewport: null,
  viewportVersion: 0,
  selectedVehicleId: null,
  dataMode: 'vehicles',
  lastError: null,
  setViewport: (viewport) => {
    const viewportVersion = get().viewportVersion + 1
    set({ viewport, viewportVersion, vehicles: {}, clusters: {}, dataMode: 'vehicles' })
    return { ...viewport, viewportVersion }
  },
  setSelectedVehicle: (selectedVehicleId) => set({ selectedVehicleId }),
  setLastError: (lastError) => set({ lastError }),
  applyMessage: (message) => {
    const state = get()
    if (message.type === 'ready') return true
    if (message.type === 'error') return true
    if (message.viewportVersion !== state.viewportVersion) return false
    if (message.type === 'clusters') {
      set({
        clusters: message.clusters,
        vehicles: {},
        dataMode: 'clusters',
        viewportVersion: message.viewportVersion,
      })
      return true
    }
    const incoming = Object.fromEntries(
      message.vehicles.map((vehicle) => [vehicle.vehicleId, vehicle]),
    )
    const vehicles = message.type === 'snapshot' ? {} : { ...state.vehicles }
    for (const [id, vehicle] of Object.entries(incoming)) {
      const previous = state.vehicles[id]
      vehicles[id] = previous && previous.seq > vehicle.seq ? previous : vehicle
    }
    const selectedVehicleId =
      message.type === 'snapshot' && state.selectedVehicleId && !vehicles[state.selectedVehicleId]
        ? null
        : state.selectedVehicleId
    set({
      vehicles,
      clusters: {},
      selectedVehicleId,
      dataMode: 'vehicles',
      viewportVersion: message.viewportVersion,
    })
    return true
  },
  clearMap: () =>
    set({
      vehicles: {},
      clusters: {},
      viewport: null,
      viewportVersion: 0,
      selectedVehicleId: null,
      dataMode: 'vehicles',
      lastError: null,
    }),
}))
