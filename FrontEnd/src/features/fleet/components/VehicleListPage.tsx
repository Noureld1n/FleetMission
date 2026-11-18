import { useState } from 'react'
import { Link } from 'react-router'
import { ArrowLeft, ArrowRight, MapPinned, Truck } from 'lucide-react'
import { useAuthStore } from '@/features/auth/store/authStore'
import { useDistricts, useVehicles } from '@/features/fleet/hooks/useFleetData'
import { vehicleStatuses, type VehicleStatus } from '@/features/fleet/api/fleetApi'
import { EmptyState, ErrorState, PageSkeleton } from '@/shared/ui/Feedback'

const statusStyles: Record<VehicleStatus, string> = {
  active: 'border-emerald-400/20 bg-emerald-400/10 text-emerald-300',
  idle: 'border-slate-400/20 bg-slate-400/10 text-slate-300',
  charging: 'border-sky-400/20 bg-sky-400/10 text-sky-300',
  offline: 'border-amber-400/20 bg-amber-400/10 text-amber-300',
  fault: 'border-rose-400/20 bg-rose-400/10 text-rose-300',
}

function StatusBadge({ status }: { status: VehicleStatus }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[10px] font-semibold capitalize ${statusStyles[status]}`}
    >
      <span className="size-1.5 rounded-full bg-current" />
      {status.replace('_', ' ')}
    </span>
  )
}

export function VehicleListPage() {
  const user = useAuthStore((state) => state.user)
  const [status, setStatus] = useState<VehicleStatus | ''>('')
  const [district, setDistrict] = useState('')
  const [cursor, setCursor] = useState<string | undefined>()
  const [previous, setPrevious] = useState<Array<string | undefined>>([])
  const districts = useDistricts()
  const vehicles = useVehicles({
    status: status || undefined,
    district: district || undefined,
    cursor,
  })

  function resetFilters() {
    setStatus('')
    setDistrict('')
    setCursor(undefined)
    setPrevious([])
  }

  if (vehicles.isPending) return <PageSkeleton rows={4} />
  if (vehicles.isError)
    return (
      <ErrorState
        description="Vehicle records could not be loaded."
        onRetry={() => void vehicles.refetch()}
      />
    )

  const page = vehicles.data
  return (
    <section className="mx-auto max-w-6xl">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="text-xs font-semibold tracking-[0.18em] text-primary uppercase">
            Fleet directory
          </p>
          <h2 className="mt-2 text-3xl font-semibold tracking-tight sm:text-4xl">Vehicles</h2>
          <p className="mt-2 text-sm text-muted-foreground">
            Browse the units assigned to {user?.tenantName}.
          </p>
        </div>
        <Link
          to="/districts"
          className="inline-flex items-center gap-2 rounded-xl border border-border bg-card px-3.5 py-2.5 text-xs font-medium transition hover:border-primary/35 hover:text-primary"
        >
          <MapPinned className="size-4" />
          Manage districts
        </Link>
      </div>

      <div className="mt-7 flex flex-wrap items-end gap-3 rounded-2xl border border-border bg-card/60 p-4">
        <label className="grid gap-1.5 text-[11px] font-medium text-muted-foreground">
          Status
          <select
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as VehicleStatus | '')
              setCursor(undefined)
              setPrevious([])
            }}
            className="h-10 min-w-40 rounded-xl border border-input bg-background px-3 text-sm text-foreground outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <option value="">All statuses</option>
            {vehicleStatuses.map((value) => (
              <option key={value} value={value}>
                {value.replace('_', ' ')}
              </option>
            ))}
          </select>
        </label>
        <label className="grid gap-1.5 text-[11px] font-medium text-muted-foreground">
          District
          <select
            value={district}
            onChange={(event) => {
              setDistrict(event.target.value)
              setCursor(undefined)
              setPrevious([])
            }}
            className="h-10 min-w-48 rounded-xl border border-input bg-background px-3 text-sm text-foreground outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <option value="">All districts</option>
            {districts.data?.items.map((item) => (
              <option key={item.id} value={item.id}>
                {item.name}
              </option>
            ))}
          </select>
        </label>
        {(status || district) && (
          <button
            type="button"
            onClick={resetFilters}
            className="h-10 px-3 text-xs font-medium text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
          >
            Clear filters
          </button>
        )}
        {districts.isError && (
          <span className="text-xs text-amber-300">District filters could not be loaded.</span>
        )}
      </div>

      {page.items.length === 0 ? (
        <div className="mt-5">
          <EmptyState
            title="No vehicles match these filters"
            description="Clear one or more filters to see vehicles in your fleet."
          />
        </div>
      ) : (
        <>
          <div className="mt-5 hidden overflow-hidden rounded-2xl border border-border bg-card/60 md:block">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-border bg-muted/40 text-[10px] tracking-[0.14em] text-muted-foreground uppercase">
                <tr>
                  <th className="px-5 py-3.5 font-medium">Vehicle</th>
                  <th className="px-5 py-3.5 font-medium">Type</th>
                  <th className="px-5 py-3.5 font-medium">Status</th>
                  <th className="px-5 py-3.5 font-medium">District</th>
                  <th className="px-5 py-3.5" />
                </tr>
              </thead>
              <tbody className="divide-y divide-border/70">
                {page.items.map((vehicle) => (
                  <tr key={vehicle.id} className="transition hover:bg-muted/25">
                    <td className="px-5 py-4">
                      <Link
                        to={`/vehicles/${vehicle.id}`}
                        className="font-medium hover:text-primary"
                      >
                        {vehicle.externalRef}
                      </Link>
                      <p className="mt-1 font-mono text-[10px] text-muted-foreground">
                        {vehicle.id}
                      </p>
                    </td>
                    <td className="px-5 py-4 text-muted-foreground capitalize">
                      {vehicle.kind.replace('_', ' ')}
                    </td>
                    <td className="px-5 py-4">
                      <StatusBadge status={vehicle.status} />
                    </td>
                    <td className="px-5 py-4 text-muted-foreground">
                      {districts.data?.items.find((item) => item.id === vehicle.homeDistrictId)
                        ?.name ?? 'Unassigned'}
                    </td>
                    <td className="px-5 py-4 text-right">
                      <Link
                        aria-label={`Open ${vehicle.externalRef}`}
                        to={`/vehicles/${vehicle.id}`}
                        className="text-xs text-primary"
                      >
                        Details →
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="mt-5 grid gap-3 md:hidden">
            {page.items.map((vehicle) => (
              <Link
                key={vehicle.id}
                to={`/vehicles/${vehicle.id}`}
                className="rounded-2xl border border-border bg-card/70 p-4"
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="flex items-center gap-3">
                    <div className="grid size-9 place-items-center rounded-xl bg-primary/10 text-primary">
                      <Truck className="size-4" />
                    </div>
                    <div>
                      <p className="text-sm font-semibold">{vehicle.externalRef}</p>
                      <p className="mt-0.5 text-[10px] text-muted-foreground capitalize">
                        {vehicle.kind.replace('_', ' ')}
                      </p>
                    </div>
                  </div>
                  <StatusBadge status={vehicle.status} />
                </div>
                <p className="mt-4 text-xs text-muted-foreground">
                  {districts.data?.items.find((item) => item.id === vehicle.homeDistrictId)?.name ??
                    'Unassigned district'}
                </p>
              </Link>
            ))}
          </div>
          <div className="mt-5 flex items-center justify-between">
            <p className="text-xs text-muted-foreground">
              Showing {page.items.length} vehicles <span className="text-border">·</span> 50 per
              page
            </p>
            <div className="flex gap-2">
              <button
                type="button"
                disabled={previous.length === 0 || vehicles.isFetching}
                onClick={() => {
                  const stack = [...previous]
                  const previousCursor = stack.pop()
                  setPrevious(stack)
                  setCursor(previousCursor)
                }}
                className="inline-flex items-center gap-1.5 rounded-lg border border-border px-3 py-2 text-xs disabled:opacity-35"
              >
                <ArrowLeft className="size-3.5" />
                Previous
              </button>
              <button
                type="button"
                disabled={!page.nextCursor || vehicles.isFetching}
                onClick={() => {
                  if (page.nextCursor) {
                    setPrevious((stack) => [...stack, cursor])
                    setCursor(page.nextCursor)
                  }
                }}
                className="inline-flex items-center gap-1.5 rounded-lg border border-border px-3 py-2 text-xs disabled:opacity-35"
              >
                Next
                <ArrowRight className="size-3.5" />
              </button>
            </div>
          </div>
        </>
      )}
    </section>
  )
}
