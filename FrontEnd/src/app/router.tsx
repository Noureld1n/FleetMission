import { lazy, Suspense } from 'react'
import { Link, Navigate, Route, Routes } from 'react-router'
import { AppShell } from '@/app/AppShell'
import { PhasePage } from '@/app/PhasePage'
import { RequireAuth } from '@/app/RouteGuards'
import { UnauthorizedRedirect } from '@/app/providers'
import { LoginPage } from '@/features/auth/components/LoginPage'
import { PageSkeleton } from '@/shared/ui/Feedback'

const MapRoute = lazy(() => import('@/features/map/MapRoute'))
const VehicleDetailRoute = lazy(() => import('@/features/fleet/VehicleDetailRoute'))

function NotFoundPage() {
  return (
    <main className="grid min-h-svh place-items-center bg-background p-6 text-center text-foreground">
      <div>
        <p className="text-xs font-semibold tracking-[0.18em] text-primary uppercase">
          404 · Route not found
        </p>
        <h1 className="mt-3 text-3xl font-semibold">This mission path is unavailable</h1>
        <Link
          to="/dashboards"
          className="mt-5 inline-block text-sm text-primary underline-offset-4 hover:underline"
        >
          Return to operations
        </Link>
      </div>
    </main>
  )
}

export function AppRouter() {
  return (
    <>
      <UnauthorizedRedirect />
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<RequireAuth />}>
          <Route element={<AppShell />}>
            <Route index element={<Navigate to="/dashboards" replace />} />
            <Route
              path="dashboards"
              element={
                <PhasePage
                  eyebrow="Command overview"
                  title="Operations overview"
                  description="A clear picture of your fleet, current alerts, and the work that needs attention."
                />
              }
            />
            <Route
              path="dashboards/:id"
              element={
                <PhasePage
                  eyebrow="Dashboard workspace"
                  title="Your operations dashboard"
                  description="Saved fleet widgets and editable layouts arrive in the dashboard phase."
                />
              }
            />
            <Route
              path="map"
              element={
                <Suspense fallback={<PageSkeleton />}>
                  <MapRoute />
                </Suspense>
              }
            />
            <Route
              path="vehicles"
              element={
                <PhasePage
                  eyebrow="Fleet directory"
                  title="Vehicles"
                  description="Search, filter, and open a detailed view of each vehicle in your tenant."
                />
              }
            />
            <Route
              path="vehicles/:id"
              element={
                <Suspense fallback={<PageSkeleton rows={2} />}>
                  <VehicleDetailRoute />
                </Suspense>
              }
            />
            <Route
              path="alerts"
              element={
                <PhasePage
                  eyebrow="Signal and response"
                  title="Alert center"
                  description="Review fleet alerts and take operator actions when your role allows it."
                />
              }
            />
          </Route>
        </Route>
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </>
  )
}
