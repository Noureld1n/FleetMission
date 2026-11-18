import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ArrowLeft, Battery, Clock3, Gauge, MapPin } from 'lucide-react'
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { useDistricts, useVehicle, useVehicleHistory } from '@/features/fleet/hooks/useFleetData'
import { ApiError } from '@/shared/api/http'
import { EmptyState, ErrorState, PageSkeleton } from '@/shared/ui/Feedback'

const ranges = { '24 hours': 1, '7 days': 7 } as const
const pageStartedAt = Date.now()

export default function VehicleDetailRoute() {
  const { id = '' } = useParams()
  const [days, setDays] = useState<1 | 7>(1)
  const range = {
    from: new Date(pageStartedAt - days * 24 * 60 * 60 * 1000).toISOString(),
    to: new Date(pageStartedAt).toISOString(),
  }
  const vehicle = useVehicle(id)
  const districts = useDistricts()
  const history = useVehicleHistory(id, range)

  if (vehicle.isPending) return <PageSkeleton rows={2} />
  if (vehicle.isError) {
    const missing = vehicle.error instanceof ApiError && vehicle.error.status === 404
    return (
      <ErrorState
        title={missing ? 'Vehicle not found' : 'Could not load vehicle'}
        description={
          missing
            ? 'This vehicle may have been removed or is outside your tenant.'
            : 'Vehicle details could not be loaded.'
        }
        onRetry={missing ? undefined : () => void vehicle.refetch()}
      />
    )
  }

  const item = vehicle.data
  const districtName = districts.data?.items.find(
    (district) => district.id === item.homeDistrictId,
  )?.name
  const latest = item.latestState
  const points =
    history.data?.points.map((point) => ({
      ...point,
      time: new Date(point.bucket).toLocaleString([], {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      }),
    })) ?? []
  return (
    <section className="mx-auto max-w-6xl">
      <Link
        to="/vehicles"
        className="inline-flex items-center gap-2 text-xs font-medium text-muted-foreground hover:text-primary"
      >
        <ArrowLeft className="size-3.5" />
        All vehicles
      </Link>
      <div className="mt-5 flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="text-xs font-semibold tracking-[0.18em] text-primary uppercase">
            Vehicle profile
          </p>
          <h2 className="mt-2 text-3xl font-semibold tracking-tight sm:text-4xl">
            {item.externalRef}
          </h2>
          <p className="mt-2 text-sm text-muted-foreground">
            {item.kind.replace('_', ' ')} <span className="mx-1.5">·</span>
            {districts.isError ? 'District unavailable' : (districtName ?? 'No assigned district')}
          </p>
        </div>
        <span className="rounded-full border border-border bg-card px-3 py-1.5 text-xs font-semibold capitalize">
          {item.status}
        </span>
      </div>

      <div className="mt-7 grid gap-4 sm:grid-cols-3">
        <article className="rounded-2xl border border-border bg-card/70 p-5">
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <Battery className="size-4 text-primary" />
            Battery
          </div>
          <p className="mt-3 text-2xl font-semibold">
            {latest?.battery == null ? '—' : `${latest.battery}%`}
          </p>
          <p className="mt-1 text-[11px] text-muted-foreground">
            {latest
              ? `Updated ${latest.ts ? new Date(latest.ts).toLocaleString() : 'recently'}`
              : 'No latest telemetry available'}
          </p>
        </article>
        <article className="rounded-2xl border border-border bg-card/70 p-5">
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <Gauge className="size-4 text-primary" />
            Speed
          </div>
          <p className="mt-3 text-2xl font-semibold">
            {latest?.speed == null ? '—' : `${latest.speed} km/h`}
          </p>
          <p className="mt-1 text-[11px] text-muted-foreground">Latest reported speed</p>
        </article>
        <article className="rounded-2xl border border-border bg-card/70 p-5">
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <MapPin className="size-4 text-primary" />
            Position
          </div>
          <p className="mt-3 font-mono text-sm font-medium">
            {latest?.lat == null || latest.lon == null
              ? '—'
              : `${latest.lat.toFixed(5)}, ${latest.lon.toFixed(5)}`}
          </p>
          <p className="mt-1 text-[11px] text-muted-foreground">
            {latest?.heading == null ? 'Heading unavailable' : `Heading ${latest.heading}°`}
          </p>
        </article>
      </div>

      <article className="mt-5 rounded-2xl border border-border bg-card/70 p-5 sm:p-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <Clock3 className="size-4 text-primary" />
              <h3 className="text-sm font-semibold">Battery history</h3>
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              Aggregated telemetry for the selected period
            </p>
          </div>
          <label className="flex items-center gap-2 text-xs text-muted-foreground">
            Range
            <select
              value={days}
              onChange={(event) => setDays(Number(event.target.value) as 1 | 7)}
              className="h-9 rounded-lg border border-input bg-background px-2 text-foreground"
            >
              {Object.entries(ranges).map(([label, value]) => (
                <option key={label} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
        </div>
        {history.isPending ? (
          <div className="grid h-64 place-items-center text-xs text-muted-foreground">
            Loading history…
          </div>
        ) : history.isError ? (
          <div className="mt-5">
            <ErrorState
              description="Vehicle history could not be loaded for this time range."
              onRetry={() => void history.refetch()}
            />
          </div>
        ) : points.length === 0 ? (
          <div className="mt-5">
            <EmptyState
              title="No history in this range"
              description="This vehicle has no aggregated telemetry for the selected period."
            />
          </div>
        ) : (
          <div
            className="mt-5 h-72 w-full"
            role="img"
            aria-label="Vehicle battery percentage history chart"
          >
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={points} margin={{ top: 8, right: 12, bottom: 4, left: 0 }}>
                <CartesianGrid
                  stroke="var(--color-border)"
                  strokeDasharray="3 3"
                  vertical={false}
                />
                <XAxis
                  dataKey="time"
                  tick={{ fill: 'var(--color-muted-foreground)', fontSize: 10 }}
                  minTickGap={35}
                />
                <YAxis
                  domain={[0, 100]}
                  unit="%"
                  tick={{ fill: 'var(--color-muted-foreground)', fontSize: 10 }}
                  width={42}
                />
                <Tooltip
                  contentStyle={{
                    background: 'var(--color-card)',
                    border: '1px solid var(--color-border)',
                    borderRadius: 12,
                    fontSize: 12,
                  }}
                />
                <Line
                  type="monotone"
                  dataKey="batteryAverage"
                  name="Average battery"
                  stroke="var(--color-primary)"
                  strokeWidth={2}
                  dot={false}
                  connectNulls
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        )}
      </article>
    </section>
  )
}
