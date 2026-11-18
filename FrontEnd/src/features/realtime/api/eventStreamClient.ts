import type { QueryClient } from '@tanstack/react-query'
import { queryKeys } from '@/shared/api/queryKeys'
import {
  AlertSseMessageSchema,
  FleetMetricSseMessageSchema,
  HeartbeatSseMessageSchema,
  type AlertResponse,
  type PageResponse,
} from '@/shared/types/contracts'
import { useAuthStore } from '@/features/auth/store/authStore'
import { useRealtimeStore } from '@/features/realtime/store/realtimeStore'

const STREAM_PATH = `${(import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')}/stream/events`

type SseFrame = { event: string; id?: string; data: string }

function dispatchUnauthorized() {
  useAuthStore.getState().clearSession()
  window.dispatchEvent(new Event('fleet:unauthorized'))
}

function updateAlertCaches(client: QueryClient, alert: AlertResponse) {
  client.setQueriesData({ queryKey: queryKeys.alerts() }, (current: unknown) => {
    if (
      !current ||
      typeof current !== 'object' ||
      !('items' in current) ||
      !Array.isArray(current.items)
    )
      return current
    const page = current as PageResponse<AlertResponse>
    const index = page.items.findIndex((item) => item.id === alert.id)
    const items = [...page.items]
    if (index >= 0) items[index] = alert
    else if (alert.status !== 'resolved') items.unshift(alert)
    return { ...page, items }
  })
}

function parseFrame(raw: string): SseFrame | null {
  let event = 'message'
  let id: string | undefined
  const data: string[] = []
  for (const line of raw.replaceAll('\r', '').split('\n')) {
    if (!line || line.startsWith(':')) continue
    const separator = line.indexOf(':')
    const field = separator < 0 ? line : line.slice(0, separator)
    const value = separator < 0 ? '' : line.slice(separator + 1).replace(/^ /, '')
    if (field === 'event') event = value
    if (field === 'id' && !value.includes('\0')) id = value
    if (field === 'data') data.push(value)
  }
  if (data.length === 0 && event !== 'heartbeat') return null
  return { event, id, data: data.join('\n') }
}

export class EventStreamClient {
  private stopped = false
  private controller: AbortController | null = null
  private lastEventId: string | null = null
  private attempt = 0
  private readonly queryClient: QueryClient

  constructor(queryClient: QueryClient) {
    this.queryClient = queryClient
  }

  start(token: string) {
    this.stopped = false
    void this.run(token)
  }

  stop() {
    this.stopped = true
    this.controller?.abort()
    this.controller = null
    useRealtimeStore.getState().setEventStatus('closed')
  }

  private async run(token: string) {
    while (!this.stopped) {
      const controller = new AbortController()
      this.controller = controller
      let lastActivity = Date.now()
      let responseEstablished = false
      let receivedFrame = false
      let stale = false
      const watchdog = window.setInterval(() => {
        if (Date.now() - lastActivity > 45_000) {
          stale = true
          useRealtimeStore.getState().setEventStatus('stale')
          controller.abort()
        }
      }, 5_000)
      try {
        useRealtimeStore
          .getState()
          .setEventStatus(this.attempt === 0 ? 'connecting' : 'reconnecting')
        const headers = new Headers({
          Authorization: `Bearer ${token}`,
          Accept: 'text/event-stream',
          'Cache-Control': 'no-cache',
        })
        if (this.lastEventId) headers.set('Last-Event-ID', this.lastEventId)
        const response = await fetch(STREAM_PATH, { headers, signal: controller.signal })
        if (response.status === 401) {
          dispatchUnauthorized()
          this.stop()
          break
        }
        if (!response.ok || !response.body)
          throw new Error(`Event stream returned ${response.status}`)
        responseEstablished = true
        useRealtimeStore.getState().setEventStatus('live')
        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        let pending = ''
        while (!this.stopped) {
          const { done, value } = await reader.read()
          if (done) break
          pending = `${pending}${decoder.decode(value, { stream: true })}`
            .replaceAll('\r\n', '\n')
            .replaceAll('\r', '\n')
          let boundary = pending.indexOf('\n\n')
          while (boundary >= 0) {
            const raw = pending.slice(0, boundary)
            pending = pending.slice(boundary + 2)
            const frame = parseFrame(raw)
            if (frame) {
              receivedFrame = true
              try {
                this.handleFrame(frame)
              } catch {
                // Ignore malformed or schema-invalid events without breaking the stream.
              }
            }
            lastActivity = Date.now()
            useRealtimeStore.getState().recordEvent()
            boundary = pending.indexOf('\n\n')
          }
        }
        await reader.cancel().catch(() => undefined)
      } catch {
        if (!this.stopped && !stale) useRealtimeStore.getState().setEventStatus('reconnecting')
      } finally {
        window.clearInterval(watchdog)
        if (this.controller === controller) this.controller = null
      }
      if (this.stopped) break
      if (responseEstablished && receivedFrame) this.attempt = 0
      const delay = Math.min(30_000, 1_000 * 2 ** Math.min(this.attempt, 5))
      this.attempt += 1
      await new Promise<void>((resolve) =>
        window.setTimeout(resolve, delay * (0.75 + Math.random() * 0.5)),
      )
    }
  }

  private handleFrame(frame: SseFrame) {
    if (frame.event === 'heartbeat') {
      HeartbeatSseMessageSchema.parse({ event: frame.event, data: frame.data || undefined })
      return
    }
    if (frame.event === 'fleet.metrics') {
      const message = FleetMetricSseMessageSchema.parse({
        event: frame.event,
        data: JSON.parse(frame.data),
      })
      const point = message.data
      useRealtimeStore.getState().appendMetric(point)
      return
    }
    if (frame.event === 'alert.opened' || frame.event === 'alert.updated') {
      const message = AlertSseMessageSchema.parse({
        event: frame.event,
        id: frame.id,
        data: JSON.parse(frame.data),
      })
      this.lastEventId = message.id
      updateAlertCaches(this.queryClient, message.data)
      return
    }
    // Ignore future event types until their contracts are added.
  }
}
