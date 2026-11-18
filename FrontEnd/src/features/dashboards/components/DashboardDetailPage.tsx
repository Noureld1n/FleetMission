import { Link, useParams } from 'react-router'
import { Activity, Bell, LayoutDashboard, Map, UsersRound } from 'lucide-react'
import { useDashboard } from '@/features/dashboards/hooks/useDashboards'
import { useFleetMetrics } from '@/features/fleet/hooks/useFleetData'
import { StreamingFleetChart } from '@/features/metrics/components/StreamingFleetChart'
import { ApiError } from '@/shared/api/http'
import { EmptyState, ErrorState, PageSkeleton } from '@/shared/ui/Feedback'
import type { DashboardWidget } from '@/shared/types/contracts'

const widgetDetails = {
  map: {
    title: 'Live map',
    icon: Map,
    description: 'Live fleet positions will connect in the realtime phase.',
  },
  live_chart: { title: 'Fleet battery history', icon: Activity, description: '' },
  status_tiles: {
    title: 'Status tiles',
    icon: Activity,
    description:
      'Fleet-wide status counts need the backend fleet-summary endpoint, which is not available yet.',
  },
  alert_list: {
    title: 'Alert list',
    icon: Bell,
    description: 'Alert data and live changes will be connected in the alerts phase.',
  },
} as const

const range = {
  from: new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString(),
  to: new Date().toISOString(),
}

function DashboardWidget({ widget }: { widget: DashboardWidget }) {
  const details = widgetDetails[widget.type]
  const Icon = details.icon
  return (
    <article
      className="min-h-64 rounded-2xl border border-border bg-card/70 p-5"
      style={{
        gridColumn: `span ${Math.max(1, Math.min(widget.gridW, 2))}`,
        gridRow: `span ${Math.max(1, Math.min(widget.gridH, 2))}`,
      }}
    >
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-2 text-sm font-semibold">
          <Icon className="size-4 text-primary" />
          {details.title}
        </div>
        <span className="text-[10px] text-muted-foreground">
          Grid {widget.gridX + 1}, {widget.gridY + 1} · {widget.gridW} × {widget.gridH}
        </span>
      </div>
      {widget.type === 'live_chart' ? (
        <DashboardMetricsChart />
      ) : (
        <div className="grid min-h-48 place-items-center text-center">
          <div className="max-w-xs">
            <LayoutDashboard className="mx-auto size-6 text-muted-foreground/60" />
            <p className="mt-3 text-xs leading-5 text-muted-foreground">{details.description}</p>
          </div>
        </div>
      )}
    </article>
  )
}

function DashboardMetricsChart() {
  const metrics = useFleetMetrics(range)
  if (metrics.isPending)
    return (
      <div className="grid min-h-48 place-items-center text-xs text-muted-foreground">
        Loading fleet history…
      </div>
    )
  if (metrics.isError)
    return (
      <div className="grid min-h-48 place-items-center text-xs text-muted-foreground">
        Fleet history could not be loaded.
      </div>
    )
  return <StreamingFleetChart history={metrics.data.points} />
}

export function DashboardDetailPage() {
  const { id = '' } = useParams()
  const dashboardQuery = useDashboard(id)
  if (dashboardQuery.isPending) return <PageSkeleton rows={2} />
  if (dashboardQuery.isError) {
    const unavailable =
      dashboardQuery.error instanceof ApiError && dashboardQuery.error.status === 404
    return (
      <ErrorState
        title={unavailable ? 'Dashboard unavailable' : 'Could not load dashboard'}
        description={
          unavailable
            ? 'It may have been removed or is not shared with your account.'
            : 'This dashboard could not be loaded.'
        }
        onRetry={unavailable ? undefined : () => void dashboardQuery.refetch()}
      />
    )
  }

  const dashboard = dashboardQuery.data
  return (
    <section className="mx-auto max-w-6xl">
      <Link
        to="/dashboards"
        className="text-xs font-medium text-muted-foreground transition hover:text-primary"
      >
        ← All dashboards
      </Link>
      <div className="mt-4 flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="text-xs font-semibold tracking-[0.18em] text-primary uppercase">
            Dashboard workspace
          </p>
          <h2 className="mt-2 text-3xl font-semibold tracking-tight sm:text-4xl">
            {dashboard.name}
          </h2>
          <p className="mt-2 text-sm text-muted-foreground">
            {dashboard.shared ? 'Shared with your tenant' : 'Private dashboard'}{' '}
            <span className="mx-1.5">·</span>
            {dashboard.widgets.length} widgets
          </p>
        </div>
        <div className="inline-flex items-center gap-2 rounded-full border border-border bg-card px-3 py-1.5 text-xs text-muted-foreground">
          <UsersRound className="size-3.5" />
          Read view
        </div>
      </div>
      {dashboard.widgets.length === 0 ? (
        <div className="mt-8">
          <EmptyState
            title="This dashboard is clear for now"
            description="Dashboard widget editing arrives in a later phase. The saved dashboard and tenant sharing are working."
          />
        </div>
      ) : (
        <div className="mt-8 grid auto-rows-[minmax(16rem,auto)] gap-4 md:grid-cols-2">
          {dashboard.widgets.map((widget) => (
            <DashboardWidget key={widget.id} widget={widget} />
          ))}
        </div>
      )}
    </section>
  )
}
