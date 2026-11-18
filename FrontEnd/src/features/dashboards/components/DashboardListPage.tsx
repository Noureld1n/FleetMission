import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { ArrowUpRight, LayoutDashboard, Plus, UsersRound } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useAuthStore } from '@/features/auth/store/authStore'
import { useCreateDashboard, useDashboards } from '@/features/dashboards/hooks/useDashboards'
import { useToast } from '@/shared/ui/useToast'
import { EmptyState, ErrorState, PageSkeleton } from '@/shared/ui/Feedback'

export function DashboardListPage() {
  const user = useAuthStore((state) => state.user)
  const dashboards = useDashboards()
  const createDashboard = useCreateDashboard()
  const toast = useToast()
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [shared, setShared] = useState(false)
  const [formError, setFormError] = useState('')

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const cleanName = name.trim()
    if (!cleanName || cleanName.length > 120) {
      setFormError('Enter a name between 1 and 120 characters.')
      return
    }
    setFormError('')
    try {
      const dashboard = await createDashboard.mutateAsync({ name: cleanName, shared, widgets: [] })
      setCreating(false)
      setName('')
      setShared(false)
      toast({ kind: 'success', title: 'Dashboard created', description: dashboard.name })
    } catch {
      setFormError('The dashboard could not be created. Try again.')
    }
  }

  if (dashboards.isPending) return <PageSkeleton rows={3} />
  if (dashboards.isError)
    return (
      <ErrorState
        description="Dashboards could not be loaded."
        onRetry={() => void dashboards.refetch()}
      />
    )

  const items = dashboards.data.items
  return (
    <section className="mx-auto max-w-6xl">
      <div className="flex flex-wrap items-end justify-between gap-5">
        <div>
          <p className="text-xs font-semibold tracking-[0.18em] text-primary uppercase">
            Command overview
          </p>
          <h2 className="mt-2 text-3xl font-semibold tracking-tight sm:text-4xl">
            Your dashboards
          </h2>
          <p className="mt-3 text-sm text-muted-foreground">
            A focused view of the fleet workspace for {user?.tenantName}.
          </p>
        </div>
        {user?.role === 'operator' && (
          <Button onClick={() => setCreating((value) => !value)}>
            <Plus className="size-4" />
            New dashboard
          </Button>
        )}
      </div>

      {creating && (
        <form
          onSubmit={submit}
          className="mt-7 grid gap-4 rounded-2xl border border-primary/20 bg-primary/[0.035] p-5 sm:grid-cols-[1fr_auto_auto] sm:items-end"
        >
          <div className="grid gap-2">
            <label htmlFor="dashboard-name" className="text-sm font-medium">
              Dashboard name
            </label>
            <input
              id="dashboard-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              maxLength={120}
              autoFocus
              placeholder="e.g. Morning operations"
              className="h-10 rounded-xl border border-input bg-card px-3 text-sm outline-none focus-visible:border-primary focus-visible:ring-3 focus-visible:ring-primary/15"
            />
            {formError && (
              <p role="alert" className="text-xs text-destructive">
                {formError}
              </p>
            )}
          </div>
          <label className="flex h-10 items-center gap-2 text-sm text-muted-foreground">
            <input
              type="checkbox"
              checked={shared}
              onChange={(event) => setShared(event.target.checked)}
              className="size-4 accent-primary"
            />
            Share with tenant
          </label>
          <div className="flex gap-2">
            <Button type="button" variant="outline" onClick={() => setCreating(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={createDashboard.isPending}>
              {createDashboard.isPending ? 'Creating…' : 'Create'}
            </Button>
          </div>
        </form>
      )}

      {items.length === 0 ? (
        <div className="mt-8">
          <EmptyState
            title="No dashboards yet"
            description={
              user?.role === 'operator'
                ? 'Create a dashboard to start shaping an operations view.'
                : 'A dashboard will appear here when one is shared with your team.'
            }
            action={
              user?.role === 'operator' ? (
                <Button onClick={() => setCreating(true)}>
                  <Plus className="size-4" />
                  Create dashboard
                </Button>
              ) : undefined
            }
          />
        </div>
      ) : (
        <div className="mt-8 grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {items.map((dashboard) => (
            <Link
              key={dashboard.id}
              to={`/dashboards/${dashboard.id}`}
              className="group rounded-2xl border border-border bg-card/75 p-5 transition duration-200 hover:-translate-y-0.5 hover:border-primary/35 hover:bg-card hover:shadow-xl hover:shadow-black/10 focus-visible:ring-2 focus-visible:ring-ring"
            >
              <div className="flex items-start justify-between gap-3">
                <div className="grid size-10 place-items-center rounded-xl bg-primary/10 text-primary">
                  <LayoutDashboard className="size-5" />
                </div>
                <span className="rounded-full border border-border px-2.5 py-1 text-[10px] font-medium text-muted-foreground">
                  {dashboard.ownerId === user?.id ? 'Owned by you' : 'Shared'}
                </span>
              </div>
              <h3 className="mt-5 flex items-center gap-2 text-base font-semibold tracking-tight">
                {dashboard.name}
                <ArrowUpRight className="size-4 text-muted-foreground opacity-0 transition group-hover:opacity-100" />
              </h3>
              <div className="mt-3 flex items-center gap-2 text-xs text-muted-foreground">
                <span>{dashboard.widgets.length} widgets</span>
                <span className="text-border">·</span>
                {dashboard.shared ? (
                  <>
                    <UsersRound className="size-3.5" />
                    Shared with team
                  </>
                ) : (
                  'Private workspace'
                )}
              </div>
              <p className="mt-5 border-t border-border/70 pt-4 text-[11px] text-muted-foreground">
                Updated {new Date(dashboard.updatedAt).toLocaleDateString()}
              </p>
            </Link>
          ))}
        </div>
      )}
    </section>
  )
}
