import { useEffect, useRef } from 'react'
import uPlot from 'uplot'
import 'uplot/dist/uPlot.min.css'
import { useRealtimeStore } from '@/features/realtime/store/realtimeStore'
import type { FleetMetricPoint } from '@/shared/types/contracts'

type ChartData = [timestamps: number[], values: (number | null)[]]

function upsertPoints(buffer: ChartData, points: FleetMetricPoint[]) {
  for (const point of points) {
    const timestamp = Math.floor(Date.parse(point.bucket) / 1000)
    if (!Number.isFinite(timestamp)) continue
    const [timestamps, values] = buffer
    let low = 0
    let high = timestamps.length
    while (low < high) {
      const middle = (low + high) >>> 1
      if (timestamps[middle] < timestamp) low = middle + 1
      else high = middle
    }
    if (timestamps[low] === timestamp) values[low] = point.batteryAverage
    else {
      timestamps.splice(low, 0, timestamp)
      values.splice(low, 0, point.batteryAverage)
    }
  }
}

export function StreamingFleetChart({ history, fillContainer = false }: { history: FleetMetricPoint[]; fillContainer?: boolean }) {
  const host = useRef<HTMLDivElement>(null)
  const plot = useRef<uPlot | null>(null)
  const buffer = useRef<ChartData>([[], []])
  const dirty = useRef(true)

  useEffect(() => {
    upsertPoints(buffer.current, history)
    dirty.current = true
  }, [history])

  useEffect(() => {
    const seed = useRealtimeStore.getState()
    upsertPoints(buffer.current, seed.metrics)
    const unsubscribe = useRealtimeStore.subscribe((state, previous) => {
      if (state.latestMetric === previous.latestMetric || !state.latestMetric) return
      upsertPoints(buffer.current, [state.latestMetric])
      dirty.current = true
    })
    return unsubscribe
  }, [])

  useEffect(() => {
    const element = host.current
    if (!element) return
    const chart = new uPlot(
      {
        width: Math.max(240, element.clientWidth),
        height: Math.max(120, element.clientHeight),
        scales: { x: { time: true }, y: { range: () => [0, 100] } },
        axes: [
          {
            stroke: '#94a3b8',
            grid: { stroke: 'rgba(148, 163, 184, 0.12)', width: 1 },
            ticks: { stroke: 'rgba(148, 163, 184, 0.2)', width: 1 },
            font: '10px Geist Variable, sans-serif',
          },
          {
            stroke: '#94a3b8',
            size: 42,
            values: (_plot, values) => values.map((value) => `${Math.round(value)}%`),
            grid: { stroke: 'rgba(148, 163, 184, 0.12)', width: 1 },
            ticks: { stroke: 'rgba(148, 163, 184, 0.2)', width: 1 },
            font: '10px Geist Variable, sans-serif',
          },
        ],
        series: [
          {},
          { label: 'Average battery', stroke: '#38bdf8', width: 2, points: { show: false } },
        ],
        legend: { show: false },
        cursor: { drag: { x: false, y: false } },
        padding: [8, 8, 0, 0],
      },
      buffer.current,
      element,
    )
    plot.current = chart

    const observer = new ResizeObserver(([entry]) => {
      if (!entry || entry.contentRect.width < 1 || entry.contentRect.height < 1) return
      chart.setSize({
        width: Math.floor(entry.contentRect.width),
        height: Math.floor(entry.contentRect.height),
      })
    })
    observer.observe(element)
    const timer = window.setInterval(() => {
      if (!dirty.current) return
      const cutoff = Math.floor(Date.now() / 1000) - 24 * 60 * 60
      while (buffer.current[0].length && buffer.current[0][0] < cutoff) {
        buffer.current[0].shift()
        buffer.current[1].shift()
      }
      chart.setData(buffer.current)
      dirty.current = false
    }, 500)

    return () => {
      window.clearInterval(timer)
      observer.disconnect()
      chart.destroy()
      plot.current = null
    }
  }, [])

  return (
    <div className={fillContainer ? 'flex h-full min-h-0 flex-col' : 'mt-3'}>
      <div className="mb-2 flex items-center justify-between text-[10px] text-muted-foreground">
        <span>Average battery</span>
        <span>Last 24 hours · live updates</span>
      </div>
      <div
        ref={host}
        className={fillContainer ? 'min-h-0 min-w-0 flex-1' : 'h-48 min-w-0'}
        role="img"
        aria-label="Fleet average battery history with live updates"
      />
    </div>
  )
}
