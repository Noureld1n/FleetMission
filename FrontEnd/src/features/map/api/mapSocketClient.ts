import {
  MapClientMessageSchema,
  MapServerMessageSchema,
  type MapViewport,
} from '@/shared/types/contracts'
import { useAuthStore } from '@/features/auth/store/authStore'
import { useMapStore } from '@/features/map/store/mapStore'
import { useRealtimeStore } from '@/features/realtime/store/realtimeStore'

function socketUrl() {
  const configured = import.meta.env.VITE_WS_URL?.trim()
  if (configured) {
    const url = new URL(configured, window.location.href)
    if (url.protocol === 'http:') url.protocol = 'ws:'
    if (url.protocol === 'https:') url.protocol = 'wss:'
    if (url.pathname.endsWith('/ws/map')) return url.toString()
    url.pathname = `${url.pathname.replace(/\/$/, '').replace(/\/ws$/, '')}/ws/map`
    return url.toString()
  }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws/map`
}

function unauthorized() {
  useAuthStore.getState().clearSession()
  window.dispatchEvent(new Event('fleet:unauthorized'))
}

export class MapSocketClient {
  private stopped = false
  private socket: WebSocket | null = null
  private retryTimer: number | undefined
  private viewportTimer: number | undefined
  private authTimer: number | undefined
  private attempt = 0
  private ready = false
  private readonly token: string

  constructor(token: string) {
    this.token = token
  }

  start() {
    this.stopped = false
    this.connect()
  }

  stop() {
    this.stopped = true
    window.clearTimeout(this.retryTimer)
    window.clearTimeout(this.viewportTimer)
    window.clearTimeout(this.authTimer)
    this.socket?.close(1000, 'Session ended')
    this.socket = null
    useRealtimeStore.getState().setMapStatus('closed')
  }

  queueViewport(viewport: Omit<MapViewport, 'viewportVersion'>) {
    window.clearTimeout(this.viewportTimer)
    this.viewportTimer = window.setTimeout(() => {
      const socket = this.socket
      const versioned = useMapStore.getState().setViewport(viewport)
      if (!this.ready || !socket || socket.readyState !== WebSocket.OPEN) return
      const parsed = MapClientMessageSchema.safeParse({ type: 'viewport', viewport: versioned })
      if (parsed.success) socket.send(JSON.stringify(parsed.data))
    }, 180)
  }

  private connect() {
    if (this.stopped) return
    useRealtimeStore.getState().setMapStatus(this.attempt === 0 ? 'connecting' : 'reconnecting')
    let socket: WebSocket
    try {
      socket = new WebSocket(socketUrl())
    } catch {
      this.scheduleRetry()
      return
    }
    this.socket = socket
    this.ready = false
    socket.onopen = () => {
      if (this.stopped || this.socket !== socket) return
      useRealtimeStore.getState().setMapStatus('authenticating')
      const auth = MapClientMessageSchema.parse({ type: 'auth', token: this.token })
      socket.send(JSON.stringify(auth))
      this.authTimer = window.setTimeout(
        () => socket.close(1008, 'Map authentication timed out'),
        12_000,
      )
    }
    socket.onmessage = (event) => {
      if (this.stopped || this.socket !== socket || typeof event.data !== 'string') return
      let json: unknown
      try {
        json = JSON.parse(event.data)
      } catch {
        return
      }
      const parsed = MapServerMessageSchema.safeParse(json)
      if (!parsed.success) return
      const message = parsed.data
      if (message.type === 'ready') {
        window.clearTimeout(this.authTimer)
        this.attempt = 0
        this.ready = true
        useRealtimeStore.getState().setMapStatus('live')
        this.sendCurrentViewport()
        return
      }
      if (message.type === 'error') {
        useMapStore.getState().setLastError(`${message.code}: ${message.message}`)
        if (/AUTH|UNAUTHORIZED|TOKEN/i.test(message.code)) {
          unauthorized()
          this.stop()
        }
        return
      }
      useMapStore.getState().applyMessage(message)
    }
    socket.onerror = () => {
      if (!this.stopped) useRealtimeStore.getState().setMapStatus('reconnecting')
    }
    socket.onclose = (event) => {
      this.ready = false
      if (this.socket === socket) this.socket = null
      window.clearTimeout(this.authTimer)
      if (this.stopped) return
      if (event.code === 1008) {
        unauthorized()
        this.stop()
        return
      }
      this.scheduleRetry()
    }
  }

  private sendCurrentViewport() {
    const socket = this.socket
    const state = useMapStore.getState()
    if (!this.ready || !socket || socket.readyState !== WebSocket.OPEN || !state.viewport) return
    const viewport = { ...state.viewport, viewportVersion: state.viewportVersion }
    const parsed = MapClientMessageSchema.safeParse({ type: 'viewport', viewport })
    if (parsed.success) socket.send(JSON.stringify(parsed.data))
  }

  private scheduleRetry() {
    if (this.stopped || this.retryTimer !== undefined) return
    useRealtimeStore.getState().setMapStatus('reconnecting')
    const base = Math.min(30_000, 1_000 * 2 ** Math.min(this.attempt, 5))
    this.attempt += 1
    this.retryTimer = window.setTimeout(
      () => {
        this.retryTimer = undefined
        this.connect()
      },
      base * (0.75 + Math.random() * 0.5),
    )
  }
}
