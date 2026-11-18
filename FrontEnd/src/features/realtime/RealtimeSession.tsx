import { useCallback, useEffect, useRef, type ReactNode } from 'react'
import { Outlet } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { useAuthStore } from '@/features/auth/store/authStore'
import { MapSocketClient } from '@/features/map/api/mapSocketClient'
import { useMapStore } from '@/features/map/store/mapStore'
import { EventStreamClient } from '@/features/realtime/api/eventStreamClient'
import { MapSocketContext } from '@/features/realtime/mapSocketContext'
import { useRealtimeStore } from '@/features/realtime/store/realtimeStore'
import type { MapViewport } from '@/shared/types/contracts'

export function RealtimeSession({ children }: { children?: ReactNode }) {
  const token = useAuthStore((state) => state.accessToken)
  const queryClient = useQueryClient()
  const socketRef = useRef<MapSocketClient | null>(null)

  useEffect(() => {
    if (!token) {
      useRealtimeStore.getState().resetRealtime()
      useMapStore.getState().clearMap()
      return
    }
    const events = new EventStreamClient(queryClient)
    const mapSocket = new MapSocketClient(token)
    socketRef.current = mapSocket
    events.start(token)
    mapSocket.start()
    return () => {
      events.stop()
      mapSocket.stop()
      socketRef.current = null
      useRealtimeStore.getState().resetRealtime()
      useMapStore.getState().clearMap()
    }
  }, [queryClient, token])

  const sendViewport = useCallback((viewport: Omit<MapViewport, 'viewportVersion'>) => {
    const socket = socketRef.current
    if (socket) socket.queueViewport(viewport)
    else useMapStore.getState().setViewport(viewport)
  }, [])

  return (
    <MapSocketContext.Provider value={{ sendViewport }}>
      {children ?? <Outlet />}
    </MapSocketContext.Provider>
  )
}
