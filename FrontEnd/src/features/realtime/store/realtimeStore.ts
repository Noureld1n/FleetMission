import { create } from 'zustand'
import type { FleetMetricPoint } from '@/shared/types/contracts'

export type LinkStatus =
  'standby' | 'connecting' | 'authenticating' | 'live' | 'reconnecting' | 'stale' | 'closed'

type RealtimeState = {
  eventStatus: LinkStatus
  mapStatus: LinkStatus
  lastEventAt: number | null
  metrics: FleetMetricPoint[]
  latestMetric: FleetMetricPoint | null
  setEventStatus: (status: LinkStatus) => void
  setMapStatus: (status: LinkStatus) => void
  recordEvent: () => void
  appendMetric: (point: FleetMetricPoint) => void
  resetRealtime: () => void
}

export const useRealtimeStore = create<RealtimeState>((set) => ({
  eventStatus: 'standby',
  mapStatus: 'standby',
  lastEventAt: null,
  metrics: [],
  latestMetric: null,
  setEventStatus: (eventStatus) => set({ eventStatus }),
  setMapStatus: (mapStatus) => set({ mapStatus }),
  recordEvent: () => set({ lastEventAt: Date.now() }),
  appendMetric: (point) =>
    set((state) => {
      const timestamp = Date.parse(point.bucket)
      const metrics = state.metrics.filter((entry) => Date.parse(entry.bucket) !== timestamp)
      metrics.push(point)
      metrics.sort((left, right) => Date.parse(left.bucket) - Date.parse(right.bucket))
      return { metrics: metrics.slice(-5760), latestMetric: point, lastEventAt: Date.now() }
    }),
  resetRealtime: () =>
    set({
      eventStatus: 'standby',
      mapStatus: 'standby',
      lastEventAt: null,
      metrics: [],
      latestMetric: null,
    }),
}))
