import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { Link, useBlocker, useNavigate, useParams } from 'react-router'
import {
  Activity,
  ArrowLeft,
  Bell,
  LayoutDashboard,
  Map,
  Plus,
  Save,
  Share2,
  Trash2,
  X,
} from 'lucide-react'
import { Responsive, useContainerWidth, verticalCompactor, type Layout, type ResponsiveLayouts } from 'react-grid-layout'
import 'react-grid-layout/css/styles.css'
import 'react-resizable/css/styles.css'
import { useAuthStore } from '@/features/auth/store/authStore'
import {
  useDashboard,
  useDeleteDashboard,
  useUpdateDashboard,
} from '@/features/dashboards/hooks/useDashboards'
import {
  DashboardWidgetCard,
  type DashboardWidgetDraft,
} from '@/features/dashboards/components/DashboardWidgets'
import { ApiError } from '@/shared/api/http'
import { useToast } from '@/shared/ui/useToast'
import { ConfirmDialog, EmptyState, ErrorState, PageSkeleton } from '@/shared/ui/Feedback'
import type { DashboardResponse, WidgetType } from '@/shared/types/contracts'
import type { DashboardRequest } from '@/features/dashboards/api/dashboardApi'

const breakpoints = { lg: 1200, md: 996, sm: 768, xs: 480, xxs: 0 }
const columns = { lg: 12, md: 10, sm: 6, xs: 4, xxs: 2 }
const emptyLayout: Layout = []
const widgetChoices: Array<{ type: WidgetType; title: string; icon: typeof Map }> = [
  { type: 'map', title: 'Live map', icon: Map },
  { type: 'live_chart', title: 'Fleet chart', icon: Activity },
  { type: 'status_tiles', title: 'Status tiles', icon: LayoutDashboard },
  { type: 'alert_list', title: 'Alert list', icon: Bell },
]

function initialLayouts(widgets: DashboardWidgetDraft[]): ResponsiveLayouts {
  const desktop = widgets.map((widget) => ({
    i: widget.id,
    x: Math.min(11, widget.gridX),
    y: widget.gridY,
    w: Math.min(12, Math.max(1, widget.gridW)),
    h: Math.max(1, widget.gridH),
    minW: 2,
    minH: 3,
  }))
  const stack = (cols: number) =>
    [...desktop]
      .sort((left, right) => left.y - right.y || left.x - right.x)
      .reduce<Layout>((layout, item, index) => [
        ...layout,
        { ...item, x: 0, y: index * Math.max(item.h, 4), w: cols, minW: cols },
      ], [])
  return {
    lg: desktop,
    md: stack(columns.md),
    sm: stack(columns.sm),
    xs: stack(columns.xs),
    xxs: stack(columns.xxs),
  }
}

function normalizeLayout(layout: Layout, breakpoint: string): Layout {
  if (breakpoint === 'lg') return layout.map((item) => ({ ...item }))
  const cols = columns[breakpoint as keyof typeof columns] ?? 12
  const scale = 12 / cols
  return layout.map((item) => ({
    ...item,
    x: Math.min(11, Math.round(item.x * scale)),
    w: Math.min(12, Math.max(1, Math.round(item.w * scale))),
  }))
}

function requestFor(
  name: string,
  shared: boolean,
  widgets: DashboardWidgetDraft[],
  layout: Layout,
): DashboardRequest {
  return {
    name: name.trim(),
    shared,
    widgets: widgets.map((widget) => {
      const position = layout.find((item) => item.i === widget.id)
      return {
        type: widget.type,
        config: widget.config,
        gridX: position?.x ?? widget.gridX,
        gridY: position?.y ?? widget.gridY,
        gridW: position?.w ?? widget.gridW,
        gridH: position?.h ?? widget.gridH,
      }
    }),
  }
}

function signature(request: DashboardRequest) {
  const widgets = request.widgets
    .map((widget) => ({ ...widget }))
    .sort((left, right) => left.gridY - right.gridY || left.gridX - right.gridX || left.type.localeCompare(right.type))
  return JSON.stringify({ name: request.name, shared: request.shared, widgets })
}

function widgetDrafts(dashboard: DashboardResponse): DashboardWidgetDraft[] {
  return dashboard.widgets.map((widget) => ({ ...widget, config: { ...widget.config } }))
}

function makeWidget(type: WidgetType, layout: Layout): DashboardWidgetDraft {
  const id = `local-${crypto.randomUUID()}`
  const height = type === 'map' ? 8 : type === 'status_tiles' ? 3 : 5
  return {
    id,
    type,
    config: {},
    gridX: 0,
    gridY: Math.max(0, ...layout.map((item) => item.y + item.h)),
    gridW: type === 'map' ? 12 : 6,
    gridH: height,
  }
}

function DashboardWorkspace({ dashboard }: { dashboard: DashboardResponse }) {
  const user = useAuthStore((state) => state.user)
  const canEdit = user?.role === 'operator' && user.id === dashboard.ownerId
  const [savedDashboard, setSavedDashboard] = useState(dashboard)
  const [widgets, setWidgets] = useState(() => widgetDrafts(dashboard))
  const [layouts, setLayouts] = useState<ResponsiveLayouts>(() => initialLayouts(widgetDrafts(dashboard)))
  const [name, setName] = useState(dashboard.name)
  const [shared, setShared] = useState(dashboard.shared)
  const [editing, setEditing] = useState(false)
  const breakpointRef = useRef('lg')
  const [selectedWidget, setSelectedWidget] = useState<WidgetType>('live_chart')
  const [formError, setFormError] = useState('')
  const [confirmDiscard, setConfirmDiscard] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const updateMutation = useUpdateDashboard()
  const deleteMutation = useDeleteDashboard()
  const toast = useToast()
  const navigate = useNavigate()
  const { width, containerRef, mounted } = useContainerWidth({ measureBeforeMount: true })
  const currentLayout = layouts.lg ?? emptyLayout
  const draftRequest = useMemo(
    () => requestFor(name, shared, widgets, currentLayout),
    [name, shared, widgets, currentLayout],
  )
  const savedRequest = useMemo(
    () => requestFor(savedDashboard.name, savedDashboard.shared, widgetDrafts(savedDashboard), initialLayouts(widgetDrafts(savedDashboard)).lg ?? []),
    [savedDashboard],
  )
  const isDirty = editing && signature(draftRequest) !== signature(savedRequest)
  const blocker = useBlocker(isDirty)
  const hasMap = widgets.some((widget) => widget.type === 'map')

  useEffect(() => {
    if (!isDirty) return
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', handleBeforeUnload)
    return () => window.removeEventListener('beforeunload', handleBeforeUnload)
  }, [isDirty])

  const widgetLayoutChanged = (activeLayout: Layout, allLayouts: ResponsiveLayouts) => {
    if (!editing) return
    const canonical = normalizeLayout(activeLayout, breakpointRef.current)
    setLayouts({ ...allLayouts, lg: canonical })
  }

  function resetDraft() {
    setWidgets(widgetDrafts(savedDashboard))
    setLayouts(initialLayouts(widgetDrafts(savedDashboard)))
    setName(savedDashboard.name)
    setShared(savedDashboard.shared)
    setFormError('')
    setEditing(false)
    setConfirmDiscard(false)
  }

  function addWidget(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (selectedWidget === 'map' && hasMap) {
      setFormError('A dashboard can contain only one live map widget.')
      return
    }
    const widget = makeWidget(selectedWidget, currentLayout)
    setWidgets((current) => [...current, widget])
    setLayouts((current) => {
      const next = { ...current }
      for (const [key, cols] of Object.entries(columns)) {
        const layout = [...(next[key] ?? [])]
        layout.push({ i: widget.id, x: 0, y: Math.max(0, ...layout.map((item) => item.y + item.h)), w: key === 'lg' ? widget.gridW : cols, h: widget.gridH, minW: key === 'lg' ? 2 : cols, minH: 3 })
        next[key] = layout
      }
      return next
    })
    setFormError('')
  }

  function removeWidget(id: string) {
    setWidgets((current) => current.filter((widget) => widget.id !== id))
    setLayouts((current) => Object.fromEntries(Object.entries(current).map(([key, layout]) => [key, layout?.filter((item) => item.i !== id)])))
  }

  async function save() {
    if (!draftRequest.name || draftRequest.name.length > 120) {
      setFormError('Dashboard name must contain between 1 and 120 characters.')
      return
    }
    if (widgets.filter((widget) => widget.type === 'map').length > 1) {
      setFormError('A dashboard can contain only one live map widget.')
      return
    }
    setFormError('')
    try {
      const updated = await updateMutation.mutateAsync({ id: dashboard.id, request: draftRequest })
      setSavedDashboard(updated)
      setWidgets(widgetDrafts(updated))
      setLayouts(initialLayouts(widgetDrafts(updated)))
      setName(updated.name)
      setShared(updated.shared)
      setEditing(false)
      toast({ kind: 'success', title: 'Dashboard saved', description: updated.name })
    } catch (error) {
      setFormError(error instanceof ApiError ? error.message : 'Dashboard could not be saved. Try again.')
    }
  }

  async function removeDashboard() {
    try {
      await deleteMutation.mutateAsync(dashboard.id)
      toast({ kind: 'success', title: 'Dashboard deleted', description: savedDashboard.name })
      void navigate('/dashboards', { replace: true })
    } catch {
      setFormError('Dashboard could not be deleted. Try again.')
      setConfirmDelete(false)
    }
  }

  const alertLabel = savedDashboard.shared ? 'Shared with your tenant' : 'Private dashboard'
  return (
    <section className="mx-auto max-w-7xl">
      <Link to="/dashboards" className="inline-flex items-center gap-2 text-xs font-medium text-muted-foreground transition hover:text-primary">
        <ArrowLeft className="size-3.5" />All dashboards
      </Link>
      <div className="mt-4 flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0 flex-1">
          <p className="text-xs font-semibold tracking-[0.18em] text-primary uppercase">Dashboard workspace</p>
          {editing ? (
            <div className="mt-2 grid gap-3 sm:grid-cols-[minmax(14rem,1fr)_auto] sm:items-center">
              <input aria-label="Dashboard name" value={name} onChange={(event) => setName(event.target.value)} maxLength={120} className="h-11 rounded-xl border border-input bg-card px-3 text-xl font-semibold outline-none focus-visible:ring-2 focus-visible:ring-ring sm:text-2xl" />
              <label className="flex items-center gap-2 text-xs text-muted-foreground"><input type="checkbox" checked={shared} onChange={(event) => setShared(event.target.checked)} className="size-4 accent-primary" /><Share2 className="size-3.5" />Share with tenant</label>
            </div>
          ) : <h2 className="mt-2 truncate text-3xl font-semibold tracking-tight sm:text-4xl">{savedDashboard.name}</h2>}
          <p className="mt-2 text-sm text-muted-foreground">{editing ? shared ? 'Changes will be shared with your tenant after saving.' : 'Only you can view this dashboard.' : alertLabel} <span className="mx-1.5">·</span>{widgets.length} widgets</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {canEdit && !editing && <button type="button" onClick={() => setEditing(true)} className="inline-flex items-center gap-2 rounded-xl border border-border bg-card px-3.5 py-2.5 text-xs font-medium transition hover:border-primary/40 hover:text-primary"><LayoutDashboard className="size-4" />Edit dashboard</button>}
          {canEdit && !editing && <button type="button" onClick={() => setConfirmDelete(true)} className="inline-flex items-center gap-2 rounded-xl border border-destructive/20 px-3.5 py-2.5 text-xs font-medium text-destructive transition hover:bg-destructive/10"><Trash2 className="size-4" />Delete</button>}
          {canEdit && editing && <>
            <button type="button" onClick={() => setConfirmDiscard(true)} className="inline-flex items-center gap-2 rounded-xl border border-border px-3.5 py-2.5 text-xs font-medium text-muted-foreground hover:text-foreground"><X className="size-4" />Cancel</button>
            <button type="button" onClick={() => void save()} disabled={updateMutation.isPending || !isDirty} className="inline-flex items-center gap-2 rounded-xl bg-primary px-3.5 py-2.5 text-xs font-semibold text-primary-foreground transition hover:brightness-105 disabled:opacity-45"><Save className="size-4" />{updateMutation.isPending ? 'Saving…' : 'Save changes'}</button>
          </>}
        </div>
      </div>

      {formError && <div role="alert" className="mt-4 rounded-xl border border-destructive/20 bg-destructive/[0.04] px-4 py-3 text-xs text-destructive">{formError}</div>}

      {editing && <form onSubmit={addWidget} className="mt-6 flex flex-wrap items-end gap-3 rounded-2xl border border-primary/20 bg-primary/[0.035] p-4">
        <label className="grid gap-1.5 text-[11px] font-medium text-muted-foreground">Add a widget<select value={selectedWidget} onChange={(event) => setSelectedWidget(event.target.value as WidgetType)} className="h-10 min-w-48 rounded-xl border border-input bg-background px-3 text-sm text-foreground">{widgetChoices.map(({ type, title }) => <option key={type} value={type} disabled={type === 'map' && hasMap}>{title}{type === 'map' && hasMap ? ' · already added' : ''}</option>)}</select></label>
        <button type="submit" disabled={selectedWidget === 'map' && hasMap} className="inline-flex h-10 items-center gap-2 rounded-xl border border-border bg-card px-3.5 text-xs font-medium hover:border-primary/40 hover:text-primary disabled:opacity-50"><Plus className="size-4" />Add widget</button>
        <p className="ml-auto text-[10px] text-muted-foreground">Drag widgets by their header; resize from the lower-right corner.</p>
      </form>}

      {widgets.length === 0 ? (
        <div className="mt-7"><EmptyState title={editing ? 'Add your first widget' : 'This dashboard is clear for now'} description={editing ? 'Choose a map, fleet chart, status tile group, or alert list above.' : 'This saved dashboard has no widgets yet.'} action={!editing && canEdit ? <button type="button" onClick={() => setEditing(true)} className="rounded-lg bg-primary px-3 py-2 text-xs font-semibold text-primary-foreground">Start editing</button> : undefined} /></div>
      ) : (
        <div ref={containerRef} className={`dashboard-grid-container mt-7 min-h-24 ${editing ? 'is-editing' : ''}`}>
          {mounted && <Responsive
            width={width}
            layouts={layouts}
            breakpoints={breakpoints}
            cols={columns}
            rowHeight={38}
            margin={[16, 16]}
            containerPadding={[0, 0]}
            compactor={verticalCompactor}
            dragConfig={{ enabled: editing, handle: '.dashboard-widget-handle', cancel: 'button, a, input, select, textarea, .dashboard-widget-map, .dashboard-widget-map *', bounded: true, allowMobileScroll: true }}
            resizeConfig={{ enabled: editing, handles: ['se'] }}
            onBreakpointChange={(value) => { breakpointRef.current = value }}
            onLayoutChange={widgetLayoutChanged}
          >
            {widgets.map((widget) => <div key={widget.id} className="h-full min-h-0"><DashboardWidgetCard widget={widget} editable={editing} duplicateMap={widget.type === 'map' && widgets.some((item) => item.type === 'map' && item.id !== widget.id)} onRemove={removeWidget} /></div>)}
          </Responsive>}
        </div>
      )}

      <p className="mt-5 text-[10px] text-muted-foreground">{isDirty ? 'Unsaved dashboard changes' : `Updated ${new Date(savedDashboard.updatedAt).toLocaleString()}`}</p>

      <ConfirmDialog open={confirmDiscard} title="Discard dashboard edits?" description="Your unsaved layout and sharing changes will be lost." confirmLabel="Discard changes" onCancel={() => setConfirmDiscard(false)} onConfirm={resetDraft} />
      <ConfirmDialog open={confirmDelete} title="Delete this dashboard?" description={`“${savedDashboard.name}” and its widget layout will be deleted permanently.`} confirmLabel={deleteMutation.isPending ? 'Deleting…' : 'Delete dashboard'} onCancel={() => setConfirmDelete(false)} onConfirm={() => void removeDashboard()} />
      <ConfirmDialog open={blocker.state === 'blocked'} title="Leave without saving?" description="This dashboard has unsaved changes. Leaving now will discard them." confirmLabel="Leave page" onCancel={() => blocker.reset?.()} onConfirm={() => blocker.proceed?.()} />
    </section>
  )
}

export function DashboardDetailPage() {
  const { id = '' } = useParams()
  const dashboardQuery = useDashboard(id)
  if (dashboardQuery.isPending) return <PageSkeleton rows={2} />
  if (dashboardQuery.isError) {
    const unavailable = dashboardQuery.error instanceof ApiError && dashboardQuery.error.status === 404
    return <ErrorState title={unavailable ? 'Dashboard unavailable' : 'Could not load dashboard'} description={unavailable ? 'It may have been removed or is not shared with your account.' : 'This dashboard could not be loaded.'} onRetry={unavailable ? undefined : () => void dashboardQuery.refetch()} />
  }
  return <DashboardWorkspace key={dashboardQuery.data.id} dashboard={dashboardQuery.data} />
}
