import { Activity, Bell, LayoutDashboard, Map, Trash2 } from 'lucide-react'
import { Link } from 'react-router'
import { useFleetMetrics } from '@/features/fleet/hooks/useFleetData'
import { useAlerts } from '@/features/alerts/hooks/useAlerts'
import { DashboardMapWidget } from '@/features/dashboards/components/DashboardMapWidget'
import { StreamingFleetChart } from '@/features/metrics/components/StreamingFleetChart'
import { EmptyState, ErrorState, PageSkeleton } from '@/shared/ui/Feedback'
import type { DashboardWidget, WidgetType } from '@/shared/types/contracts'

export type DashboardWidgetDraft = Omit<DashboardWidget, 'id'> & { id: string }

const widgetMetadata: Record<WidgetType, { title: string; icon: typeof Map }> = {
  map: { title: 'Live map', icon: Map },
  live_chart: { title: 'Fleet battery history', icon: Activity },
  status_tiles: { title: 'Fleet status', icon: Activity },
  alert_list: { title: 'Open alerts', icon: Bell },
}

export function DashboardWidgetCard({
  widget,
  editable,
  duplicateMap,
  onRemove,
}: {
  widget: DashboardWidgetDraft
  editable: boolean
  duplicateMap: boolean
  onRemove: (id: string) => void
}) {
  const metadata = widgetMetadata[widget.type]
  const Icon = metadata.icon
  return (
    <article className="flex h-full min-h-0 flex-col overflow-hidden rounded-2xl border border-border bg-card/85 shadow-lg shadow-black/5">
      <header
        className={`dashboard-widget-handle flex min-h-12 items-center justify-between gap-3 border-b border-border/80 px-4 py-3 ${editable ? 'cursor-grab active:cursor-grabbing' : ''}`}
      >
        <div className="flex min-w-0 items-center gap-2 text-sm font-semibold">
          <Icon className="size-4 shrink-0 text-primary" />
          <span className="truncate">{metadata.title}</span>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <span className="hidden text-[10px] text-muted-foreground sm:inline">
            {widget.gridW} × {widget.gridH}
          </span>
          {editable && (
            <button
              type="button"
              aria-label={`Remove ${metadata.title} widget`}
              onClick={() => onRemove(widget.id)}
              className="rounded-md p-1.5 text-muted-foreground transition hover:bg-destructive/10 hover:text-destructive"
            >
              <Trash2 className="size-3.5" />
            </button>
          )}
        </div>
      </header>
      <div className={`min-h-0 flex-1 p-3 ${widget.type === 'map' || widget.type === 'live_chart' ? 'flex flex-col' : ''}`}>
        <WidgetBody widget={widget} duplicateMap={duplicateMap} />
      </div>
    </article>
  )
}

function WidgetBody({ widget, duplicateMap }: { widget: DashboardWidgetDraft; duplicateMap: boolean }) {
  if (widget.type === 'map') {
    return duplicateMap ? (
      <div className="grid min-h-52 flex-1 place-items-center rounded-xl border border-dashed border-amber-300/20 bg-amber-300/[0.035] p-5 text-center text-xs text-amber-100">
        Only one live map can be active in a dashboard. Remove this duplicate map widget.
      </div>
    ) : (
      <DashboardMapWidget />
    )
  }
  if (widget.type === 'live_chart') return <DashboardLiveChart />
  if (widget.type === 'status_tiles') return <StatusTilesPending />
  return <DashboardAlertsWidget config={widget.config} />
}

const metricsRange = {
  from: new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString(),
  to: new Date().toISOString(),
}

function DashboardLiveChart() {
  const query = useFleetMetrics(metricsRange)
  if (query.isPending) return <PageSkeleton rows={1} />
  if (query.isError)
    return (
      <ErrorState
        description="Fleet history is unavailable right now."
        onRetry={() => void query.refetch()}
      />
    )
  return <StreamingFleetChart history={query.data.points} fillContainer />
}

function StatusTilesPending() {
  return (
    <div className="grid min-h-48 place-items-center rounded-xl border border-dashed border-border bg-background/40 p-5 text-center">
      <div className="max-w-sm">
        <LayoutDashboard className="mx-auto size-5 text-muted-foreground" />
        <p className="mt-3 text-xs font-semibold">Fleet-wide counts are not available yet</p>
        <p className="mt-2 text-[11px] leading-5 text-muted-foreground">
          Status tiles need a tenant-scoped fleet summary endpoint. Counts are intentionally left blank until that endpoint exists.
        </p>
      </div>
    </div>
  )
}

function DashboardAlertsWidget({ config }: { config: Record<string, unknown> }) {
  const status = ['open', 'acknowledged', 'resolved'].includes(String(config.status))
    ? String(config.status)
    : 'open'
  const severity = ['info', 'warning', 'critical'].includes(String(config.severity))
    ? String(config.severity)
    : undefined
  const query = useAlerts({ limit: 6, status, severity })
  if (query.isPending) return <PageSkeleton rows={1} />
  if (query.isError)
    return (
      <ErrorState
        description="Alerts could not be loaded for this widget."
        onRetry={() => void query.refetch()}
      />
    )
  if (query.data.items.length === 0)
    return (
      <EmptyState
        title="No matching alerts"
        description="New open alerts for this tenant will appear here."
      />
    )

  return (
    <div className="grid gap-2">
      {query.data.items.map((alert) => {
        const color = alert.severity === 'critical' ? 'text-rose-300' : alert.severity === 'warning' ? 'text-amber-200' : 'text-sky-200'
        const message = typeof alert.detail.message === 'string' ? alert.detail.message : alert.kind.replaceAll('_', ' ')
        return (
          <div key={alert.id} className="rounded-xl border border-border/80 bg-background/40 p-3">
            <div className="flex items-start justify-between gap-3">
              <p className="text-xs font-medium capitalize">{message}</p>
              <span className={`shrink-0 text-[9px] font-semibold uppercase ${color}`}>{alert.severity}</span>
            </div>
            <div className="mt-2 flex items-center justify-between gap-2 text-[10px] text-muted-foreground">
              <span>{new Date(alert.openedAt).toLocaleString()}</span>
              {alert.vehicleId ? <Link className="text-primary hover:underline" to={`/vehicles/${alert.vehicleId}`}>Vehicle</Link> : alert.districtId ? <Link className="text-primary hover:underline" to="/districts">District</Link> : <span className="capitalize">{alert.status}</span>}
            </div>
          </div>
        )
      })}
    </div>
  )
}
