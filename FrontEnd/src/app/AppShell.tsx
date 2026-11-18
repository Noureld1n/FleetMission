import { useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import {
  Activity,
  Bell,
  ChevronRight,
  Command,
  LayoutDashboard,
  LogOut,
  Map,
  MapPinned,
  Menu,
  Truck,
  X,
} from 'lucide-react'
import { queryClient } from '@/app/queryClient'
import { useAuthStore } from '@/features/auth/store/authStore'
import { ConnectionBadge } from '@/shared/ui/Feedback'

const navigation = [
  { to: '/dashboards', label: 'Overview', icon: LayoutDashboard },
  { to: '/map', label: 'Live map', icon: Map },
  { to: '/vehicles', label: 'Vehicles', icon: Truck },
  { to: '/districts', label: 'Districts', icon: MapPinned },
  { to: '/alerts', label: 'Alerts', icon: Bell },
]

function pageTitle(pathname: string) {
  if (pathname.startsWith('/map')) return 'Live fleet map'
  if (pathname.startsWith('/vehicles')) return 'Vehicles'
  if (pathname.startsWith('/districts')) return 'Districts'
  if (pathname.startsWith('/alerts')) return 'Alert center'
  if (pathname.startsWith('/dashboards/')) return 'Dashboard workspace'
  return 'Operations overview'
}

export function AppShell() {
  const [mobileNavOpen, setMobileNavOpen] = useState(false)
  const location = useLocation()
  const navigate = useNavigate()
  const user = useAuthStore((state) => state.user)
  const clearSession = useAuthStore((state) => state.clearSession)

  function logout() {
    clearSession()
    queryClient.clear()
    void navigate('/login', { replace: true })
  }

  const navLinks = navigation.map(({ to, label, icon: Icon }) => (
    <NavLink
      key={to}
      to={to}
      onClick={() => setMobileNavOpen(false)}
      className={({ isActive }) =>
        `group flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-colors ${
          isActive
            ? 'bg-primary/12 text-primary ring-1 ring-inset ring-primary/15'
            : 'text-muted-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'
        }`
      }
    >
      <Icon className="size-[18px] shrink-0" strokeWidth={1.8} aria-hidden="true" />
      <span>{label}</span>
      {label === 'Alerts' && (
        <span
          className="ml-auto size-1.5 rounded-full bg-amber-400"
          aria-label="Alerts may need attention"
        />
      )}
    </NavLink>
  ))

  return (
    <div className="min-h-svh bg-background text-foreground">
      {mobileNavOpen && (
        <button
          type="button"
          aria-label="Close navigation"
          className="fixed inset-0 z-40 bg-black/60 backdrop-blur-sm md:hidden"
          onClick={() => setMobileNavOpen(false)}
        />
      )}
      <aside
        className={`fixed inset-y-0 left-0 z-50 flex w-[264px] flex-col border-r border-sidebar-border bg-sidebar px-4 py-5 transition-transform md:translate-x-0 ${mobileNavOpen ? 'translate-x-0' : '-translate-x-full md:translate-x-0'}`}
        aria-label="Main navigation"
      >
        <div className="flex items-center gap-3 px-2">
          <div className="grid size-10 place-items-center rounded-xl bg-primary text-primary-foreground shadow-lg shadow-primary/15">
            <Command className="size-5" strokeWidth={2.2} aria-hidden="true" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold tracking-tight">Fleet Mission</p>
            <p className="mt-0.5 text-[10px] font-semibold tracking-[0.2em] text-muted-foreground uppercase">
              Control center
            </p>
          </div>
          <button
            className="rounded-lg p-2 text-muted-foreground hover:bg-sidebar-accent md:hidden"
            onClick={() => setMobileNavOpen(false)}
            aria-label="Close menu"
          >
            <X className="size-4" />
          </button>
        </div>

        <div className="mt-9 px-3 text-[10px] font-semibold tracking-[0.18em] text-muted-foreground/75 uppercase">
          Workspace
        </div>
        <nav className="mt-3 grid gap-1">{navLinks}</nav>

        <div className="mt-auto rounded-2xl border border-sidebar-border bg-sidebar-accent/45 p-3.5">
          <ConnectionBadge status="standby" />
          <p className="mt-2 text-xs leading-5 text-muted-foreground">
            Realtime connections will start with the live operations features.
          </p>
        </div>
      </aside>

      <div className="md:pl-[264px]">
        <header className="sticky top-0 z-30 flex h-[72px] items-center gap-4 border-b border-border/80 bg-background/85 px-4 backdrop-blur-xl sm:px-7">
          <button
            type="button"
            onClick={() => setMobileNavOpen(true)}
            className="rounded-lg p-2 text-muted-foreground hover:bg-muted md:hidden"
            aria-label="Open navigation"
          >
            <Menu className="size-5" />
          </button>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <span>Fleet Mission</span>
              <ChevronRight className="size-3" />
              <span className="truncate text-foreground/80">{pageTitle(location.pathname)}</span>
            </div>
            <h1 className="mt-1 truncate text-sm font-semibold tracking-tight sm:text-base">
              {pageTitle(location.pathname)}
            </h1>
          </div>
          <div className="hidden items-center gap-2 rounded-full border border-border bg-card/75 px-3 py-1.5 text-xs text-muted-foreground sm:flex">
            <Activity className="size-3.5 text-primary" />
            {user?.tenantName ?? 'Fleet workspace'}
          </div>
          <div className="flex items-center gap-3 border-l border-border pl-3 sm:pl-5">
            <div className="hidden text-right sm:block">
              <p className="max-w-36 truncate text-xs font-medium">
                {user?.displayName || user?.email}
              </p>
              <p className="mt-0.5 text-[10px] tracking-wide text-muted-foreground uppercase">
                {user?.role}
              </p>
            </div>
            <div
              className="grid size-9 place-items-center rounded-full border border-primary/25 bg-primary/10 text-xs font-semibold text-primary"
              aria-hidden="true"
            >
              {(user?.displayName || user?.email || 'FM').slice(0, 1).toUpperCase()}
            </div>
            <button
              type="button"
              onClick={logout}
              className="rounded-lg p-2 text-muted-foreground transition hover:bg-destructive/10 hover:text-destructive"
              aria-label="Log out"
            >
              <LogOut className="size-4" />
            </button>
          </div>
        </header>

        <main className="mx-auto w-full max-w-[1600px] p-4 sm:p-7 lg:p-9">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
