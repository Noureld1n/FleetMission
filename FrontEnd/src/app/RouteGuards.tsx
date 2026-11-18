import { Navigate, Outlet, useLocation } from 'react-router'
import { useAuthStore } from '@/features/auth/store/authStore'
import type { UserRole } from '@/shared/types/contracts'

function RouteLoading() {
  return (
    <main className="grid min-h-svh place-items-center bg-background text-foreground">
      <div className="flex items-center gap-3 rounded-2xl border border-border bg-card px-5 py-4 text-sm text-muted-foreground shadow-xl">
        <span className="size-2 animate-pulse rounded-full bg-primary" aria-hidden="true" />
        Checking your secure session
      </div>
    </main>
  )
}

export function RequireAuth() {
  const location = useLocation()
  const bootstrapped = useAuthStore((state) => state.bootstrapped)
  const token = useAuthStore((state) => state.accessToken)
  const user = useAuthStore((state) => state.user)

  if (!bootstrapped) return <RouteLoading />
  if (!token || !user) {
    return <Navigate to="/login" replace state={{ from: location }} />
  }
  return <Outlet />
}

export function RequireRole({ role }: { role: UserRole }) {
  const user = useAuthStore((state) => state.user)
  if (!user || user.role !== role) {
    return (
      <main className="grid min-h-[50vh] place-items-center p-8 text-center">
        <div>
          <p className="text-xs font-semibold tracking-[0.18em] text-primary uppercase">
            Access restricted
          </p>
          <h1 className="mt-3 text-2xl font-semibold">Operator access required</h1>
          <p className="mt-2 text-sm text-muted-foreground">
            Your account can view fleet operations but cannot make this change.
          </p>
        </div>
      </main>
    )
  }
  return <Outlet />
}
