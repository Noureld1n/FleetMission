import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { AlertTriangle, Check, Info, LoaderCircle, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ToastContext, type ToastItem } from '@/shared/ui/toastContext'

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])
  const nextId = useRef(0)
  const toast = useCallback((item: Omit<ToastItem, 'id'>) => {
    const id = ++nextId.current
    setItems((current) => [...current, { ...item, id }])
    window.setTimeout(() => setItems((current) => current.filter((entry) => entry.id !== id)), 5000)
  }, [])

  return (
    <ToastContext.Provider value={{ toast }}>
      {children}
      <div
        className="fixed right-4 bottom-4 z-[100] grid w-[min(24rem,calc(100vw-2rem))] gap-2"
        aria-live="polite"
        aria-relevant="additions"
      >
        {items.map((item) => {
          const Icon =
            item.kind === 'success' ? Check : item.kind === 'error' ? AlertTriangle : Info
          const color =
            item.kind === 'success'
              ? 'text-emerald-300'
              : item.kind === 'error'
                ? 'text-destructive'
                : 'text-primary'
          return (
            <div
              key={item.id}
              role={item.kind === 'error' ? 'alert' : 'status'}
              className="flex gap-3 rounded-xl border border-border bg-popover/95 p-4 text-popover-foreground shadow-2xl backdrop-blur-xl"
            >
              <Icon className={`mt-0.5 size-4 shrink-0 ${color}`} aria-hidden="true" />
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium">{item.title}</p>
                {item.description && (
                  <p className="mt-1 text-xs leading-5 text-muted-foreground">{item.description}</p>
                )}
              </div>
              <button
                type="button"
                className="-mt-1 -mr-1 rounded-md p-1 text-muted-foreground hover:text-foreground"
                aria-label="Dismiss notification"
                onClick={() =>
                  setItems((current) => current.filter((entry) => entry.id !== item.id))
                }
              >
                <X className="size-3.5" />
              </button>
            </div>
          )
        })}
      </div>
    </ToastContext.Provider>
  )
}

export function ConnectionBadge({
  status,
}: {
  status: 'live' | 'reconnecting' | 'stale' | 'standby'
}) {
  const labels = {
    live: 'Live',
    reconnecting: 'Reconnecting',
    stale: 'Data may be stale',
    standby: 'Live links coming soon',
  }
  const colors = {
    live: 'bg-emerald-400',
    reconnecting: 'bg-amber-300',
    stale: 'bg-amber-500',
    standby: 'bg-muted-foreground/70',
  }
  return (
    <div
      className="inline-flex items-center gap-2 rounded-full border border-border bg-card/75 px-3 py-1.5 text-[11px] font-medium text-muted-foreground"
      role="status"
    >
      <span
        className={`size-1.5 rounded-full ${colors[status]} ${status === 'live' ? 'shadow-[0_0_10px_currentColor]' : ''}`}
        aria-hidden="true"
      />
      <span>{labels[status]}</span>
    </div>
  )
}

export function PageSkeleton({ rows = 4 }: { rows?: number }) {
  return (
    <div className="animate-pulse space-y-4" aria-label="Loading content" aria-busy="true">
      <div className="h-7 w-52 rounded-lg bg-muted" />
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {Array.from({ length: rows }, (_, index) => (
          <div key={index} className="h-36 rounded-2xl border border-border bg-card" />
        ))}
      </div>
    </div>
  )
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string
  description: string
  action?: ReactNode
}) {
  return (
    <div className="grid min-h-56 place-items-center rounded-2xl border border-dashed border-border bg-card/40 p-8 text-center">
      <div className="max-w-sm">
        <div className="mx-auto grid size-10 place-items-center rounded-xl bg-muted text-muted-foreground">
          <Info className="size-5" />
        </div>
        <h2 className="mt-4 text-sm font-semibold">{title}</h2>
        <p className="mt-2 text-xs leading-5 text-muted-foreground">{description}</p>
        {action && <div className="mt-5">{action}</div>}
      </div>
    </div>
  )
}

export function ErrorState({
  title = 'Something went wrong',
  description,
  onRetry,
}: {
  title?: string
  description: string
  onRetry?: () => void
}) {
  return (
    <div
      className="rounded-2xl border border-destructive/20 bg-destructive/[0.04] p-5"
      role="alert"
    >
      <div className="flex gap-3">
        <AlertTriangle className="mt-0.5 size-4 shrink-0 text-destructive" />
        <div>
          <h2 className="text-sm font-semibold">{title}</h2>
          <p className="mt-1 text-xs leading-5 text-muted-foreground">{description}</p>
          {onRetry && (
            <Button variant="outline" size="sm" className="mt-4" onClick={onRetry}>
              Try again
            </Button>
          )}
        </div>
      </div>
    </div>
  )
}

export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel = 'Continue',
  onConfirm,
  onCancel,
}: {
  open: boolean
  title: string
  description: string
  confirmLabel?: string
  onConfirm: () => void
  onCancel: () => void
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  }, [open])

  return (
    <dialog
      ref={dialogRef}
      onCancel={(event) => {
        event.preventDefault()
        onCancel()
      }}
      className="m-auto w-[min(28rem,calc(100vw-2rem))] rounded-2xl border border-border bg-card p-0 text-card-foreground shadow-2xl backdrop:bg-black/65 backdrop:backdrop-blur-sm"
    >
      <div className="p-6">
        <div className="grid size-10 place-items-center rounded-xl bg-amber-300/10 text-amber-300">
          <AlertTriangle className="size-5" />
        </div>
        <h2 className="mt-4 text-lg font-semibold">{title}</h2>
        <p className="mt-2 text-sm leading-6 text-muted-foreground">{description}</p>
        <div className="mt-6 flex justify-end gap-2">
          <Button variant="outline" onClick={onCancel}>
            Cancel
          </Button>
          <Button onClick={onConfirm}>{confirmLabel}</Button>
        </div>
      </div>
    </dialog>
  )
}

export function LoadingMark() {
  return <LoaderCircle className="size-4 animate-spin" aria-label="Loading" />
}
