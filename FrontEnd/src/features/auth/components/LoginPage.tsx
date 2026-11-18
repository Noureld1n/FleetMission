import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { ArrowRight, Eye, EyeOff, Fingerprint, Radar, ShieldCheck, Sparkles } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { login, getCurrentUser } from '@/features/auth/api/authApi'
import { useAuthStore } from '@/features/auth/store/authStore'
import { ApiError } from '@/shared/api/http'
import { LoginRequestSchema } from '@/shared/types/contracts'
import { useToast } from '@/shared/ui/useToast'

type LoginLocationState = { from?: { pathname?: string }; sessionExpired?: boolean }

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const token = useAuthStore((state) => state.accessToken)
  const bootstrapped = useAuthStore((state) => state.bootstrapped)
  const setAccessToken = useAuthStore((state) => state.setAccessToken)
  const setUser = useAuthStore((state) => state.setUser)
  const finishBootstrap = useAuthStore((state) => state.finishBootstrap)
  const toast = useToast()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [emailError, setEmailError] = useState('')
  const [passwordError, setPasswordError] = useState('')

  if (!bootstrapped) {
    return (
      <main className="grid min-h-svh place-items-center text-sm text-muted-foreground">
        Restoring session…
      </main>
    )
  }
  if (token && useAuthStore.getState().user) return <Navigate to="/dashboards" replace />

  const state = location.state as LoginLocationState | null

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError('')
    setEmailError('')
    setPasswordError('')

    const parsed = LoginRequestSchema.safeParse({ email: email.trim(), password })
    if (!parsed.success) {
      const fields = parsed.error.flatten().fieldErrors
      setEmailError(fields.email?.[0] ?? '')
      setPasswordError(fields.password?.[0] ?? '')
      return
    }

    setSubmitting(true)
    try {
      const session = await login(parsed.data)
      setAccessToken(session.accessToken)
      const currentUser = await getCurrentUser()
      setUser(currentUser)
      finishBootstrap()
      toast({ kind: 'success', title: `Welcome back, ${currentUser.displayName}` })
      const destination = state?.from?.pathname ?? '/dashboards'
      void navigate(destination, { replace: true })
    } catch (cause) {
      setError(
        cause instanceof ApiError
          ? cause.message
          : 'Unable to connect. Check your connection and try again.',
      )
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="relative isolate grid min-h-svh overflow-hidden bg-background text-foreground lg:grid-cols-[1.08fr_0.92fr]">
      <div className="pointer-events-none absolute inset-0 -z-10 lg:hidden">
        <div className="absolute -top-40 left-1/2 size-[32rem] -translate-x-1/2 rounded-full bg-primary/10 blur-[120px]" />
      </div>

      <section className="relative hidden flex-col justify-between overflow-hidden border-r border-border/70 bg-[radial-gradient(ellipse_at_25%_15%,oklch(0.29_0.08_225/0.38),transparent_48%),linear-gradient(145deg,oklch(0.18_0.035_260),oklch(0.13_0.025_260))] p-10 xl:p-14 lg:flex">
        <div className="absolute inset-0 opacity-[0.14] [background-image:linear-gradient(oklch(0.8_0.04_230/0.18)_1px,transparent_1px),linear-gradient(90deg,oklch(0.8_0.04_230/0.18)_1px,transparent_1px)] [background-size:54px_54px] [mask-image:linear-gradient(to_bottom,black,transparent_80%)]" />
        <div className="relative flex items-center gap-3">
          <div className="grid size-11 place-items-center rounded-2xl border border-primary/25 bg-primary/10 text-primary shadow-lg shadow-primary/10">
            <Radar className="size-5" strokeWidth={1.8} />
          </div>
          <div>
            <p className="text-sm font-semibold tracking-tight">Fleet Mission Control</p>
            <p className="mt-0.5 text-[10px] font-semibold tracking-[0.2em] text-muted-foreground uppercase">
              Operations platform
            </p>
          </div>
        </div>

        <div className="relative max-w-xl py-16">
          <div className="mb-7 inline-flex items-center gap-2 rounded-full border border-primary/20 bg-primary/[0.07] px-3 py-1.5 text-xs text-primary">
            <Sparkles className="size-3.5" />
            One clear view of every mission
          </div>
          <h1 className="text-5xl leading-[1.08] font-semibold tracking-[-0.045em] xl:text-6xl">
            See the whole fleet.
            <br />
            <span className="bg-gradient-to-r from-primary via-cyan-200 to-sky-300 bg-clip-text text-transparent">
              Move with confidence.
            </span>
          </h1>
          <p className="mt-6 max-w-md text-base leading-7 text-muted-foreground">
            A live operations workspace for vehicle activity, district health, and the signals that
            need your attention.
          </p>

          <div className="mt-12 grid max-w-lg grid-cols-2 gap-3">
            <div className="rounded-2xl border border-white/[0.08] bg-white/[0.035] p-4 backdrop-blur-sm">
              <div className="flex items-center gap-2 text-xs font-medium text-foreground/90">
                <Radar className="size-4 text-primary" /> Live fleet picture
              </div>
              <p className="mt-2 text-xs leading-5 text-muted-foreground">
                Positions and status in one operational view.
              </p>
            </div>
            <div className="rounded-2xl border border-white/[0.08] bg-white/[0.035] p-4 backdrop-blur-sm">
              <div className="flex items-center gap-2 text-xs font-medium text-foreground/90">
                <ShieldCheck className="size-4 text-emerald-300" /> Tenant-secure
              </div>
              <p className="mt-2 text-xs leading-5 text-muted-foreground">
                Your workspace stays scoped to your organization.
              </p>
            </div>
          </div>
        </div>

        <div className="relative flex items-center justify-between text-[11px] text-muted-foreground">
          <span>Fleet operations, made clear.</span>
          <span className="flex items-center gap-2">
            <span className="size-1.5 rounded-full bg-emerald-400" /> Secure sign-in
          </span>
        </div>
      </section>

      <section className="flex min-h-svh items-center justify-center px-5 py-12 sm:px-10">
        <div className="w-full max-w-[420px]">
          <div className="mb-10 flex items-center gap-3 lg:hidden">
            <div className="grid size-10 place-items-center rounded-xl bg-primary text-primary-foreground">
              <Radar className="size-5" />
            </div>
            <div>
              <p className="text-sm font-semibold">Fleet Mission Control</p>
              <p className="text-[10px] tracking-[0.18em] text-muted-foreground uppercase">
                Operations platform
              </p>
            </div>
          </div>

          <div className="mb-8">
            <p className="text-xs font-semibold tracking-[0.18em] text-primary uppercase">
              Welcome back
            </p>
            <h2 className="mt-3 text-3xl font-semibold tracking-tight">
              Sign in to your workspace
            </h2>
            <p className="mt-2 text-sm leading-6 text-muted-foreground">
              Use your Fleet Mission Control account to continue.
            </p>
          </div>

          {state?.sessionExpired && (
            <div
              role="status"
              className="mb-5 rounded-xl border border-amber-400/20 bg-amber-400/[0.07] px-4 py-3 text-sm text-amber-100"
            >
              Your session ended. Sign in again to continue.
            </div>
          )}

          <form className="grid gap-5" onSubmit={handleSubmit} noValidate>
            <div className="grid gap-2">
              <label htmlFor="email" className="text-sm font-medium">
                Email address
              </label>
              <input
                id="email"
                name="email"
                type="email"
                autoComplete="username"
                autoFocus
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                aria-invalid={Boolean(emailError)}
                aria-describedby={emailError ? 'email-error' : undefined}
                className="h-11 rounded-xl border border-input bg-card/75 px-3.5 text-sm outline-none transition placeholder:text-muted-foreground/70 focus-visible:border-primary/65 focus-visible:ring-3 focus-visible:ring-primary/15 aria-invalid:border-destructive"
                placeholder="you@company.com"
              />
              {emailError && (
                <p id="email-error" className="text-xs text-destructive">
                  {emailError}
                </p>
              )}
            </div>

            <div className="grid gap-2">
              <label htmlFor="password" className="text-sm font-medium">
                Password
              </label>
              <div className="relative">
                <input
                  id="password"
                  name="password"
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="current-password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  aria-invalid={Boolean(passwordError)}
                  aria-describedby={passwordError ? 'password-error' : undefined}
                  className="h-11 w-full rounded-xl border border-input bg-card/75 px-3.5 pr-11 text-sm outline-none transition placeholder:text-muted-foreground/70 focus-visible:border-primary/65 focus-visible:ring-3 focus-visible:ring-primary/15 aria-invalid:border-destructive"
                  placeholder="Enter your password"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((visible) => !visible)}
                  className="absolute top-1/2 right-2.5 -translate-y-1/2 rounded-md p-1.5 text-muted-foreground hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring"
                  aria-label={showPassword ? 'Hide password' : 'Show password'}
                >
                  {showPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                </button>
              </div>
              {passwordError && (
                <p id="password-error" className="text-xs text-destructive">
                  {passwordError}
                </p>
              )}
            </div>

            {error && (
              <p
                role="alert"
                className="rounded-xl border border-destructive/25 bg-destructive/[0.08] px-4 py-3 text-sm text-destructive"
              >
                {error}
              </p>
            )}

            <Button
              type="submit"
              disabled={submitting}
              className="mt-1 h-11 w-full rounded-xl text-sm font-semibold shadow-lg shadow-primary/10"
            >
              {submitting ? 'Signing in…' : 'Sign in'}
              {!submitting && <ArrowRight className="size-4" />}
            </Button>
          </form>

          <div className="mt-7 flex items-start gap-2.5 border-t border-border pt-5 text-xs leading-5 text-muted-foreground">
            <Fingerprint className="mt-0.5 size-4 shrink-0 text-primary/80" />
            <p>
              Your access is protected by your organization and role. Contact your administrator if
              you need an account.
            </p>
          </div>
          <p className="mt-10 text-center text-[10px] tracking-wide text-muted-foreground/70">
            FLEET MISSION CONTROL <span className="mx-1.5">·</span> OPERATIONS PLATFORM
          </p>
        </div>
      </section>
    </main>
  )
}
