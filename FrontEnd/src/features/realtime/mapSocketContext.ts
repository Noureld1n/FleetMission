import { createContext, useContext } from 'react'
import type { MapViewport } from '@/shared/types/contracts'

export type MapSocketContextValue = {
  sendViewport: (viewport: Omit<MapViewport, 'viewportVersion'>) => void
}

export const MapSocketContext = createContext<MapSocketContextValue | null>(null)

export function useMapSocket() {
  const context = useContext(MapSocketContext)
  if (!context) throw new Error('useMapSocket must be used inside RealtimeSession')
  return context
}
