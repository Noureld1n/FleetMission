import { ArrowUpRight, BadgeCheck, Construction, Gauge } from 'lucide-react'
import { useAuthStore } from '@/features/auth/store/authStore'

export function PhasePage({
  title,
  eyebrow,
  description,
}: {
  title: string
  eyebrow: string
  description: string
}) {
  const tenantName = useAuthStore((state) => state.user?.tenantName ?? 'your organization')
  return (
    <section className="mx-auto max-w-5xl">
      <div className="flex flex-wrap items-end justify-between gap-5">
        <div>
          <p className="text-xs font-semibold tracking-[0.18em] text-primary uppercase">
            {eyebrow}
          </p>
          <h2 className="mt-2 text-3xl font-semibold tracking-tight sm:text-4xl">{title}</h2>
          <p className="mt-3 max-w-2xl text-sm leading-6 text-muted-foreground">{description}</p>
        </div>
        <div className="inline-flex items-center gap-2 rounded-full border border-primary/20 bg-primary/[0.07] px-3 py-1.5 text-xs font-medium text-primary">
          <BadgeCheck className="size-3.5" /> Session protected
        </div>
      </div>

      <div className="mt-9 grid gap-4 md:grid-cols-3">
        <div className="rounded-2xl border border-border bg-card/80 p-5 shadow-lg shadow-black/5">
          <div className="grid size-10 place-items-center rounded-xl bg-primary/10 text-primary">
            <Gauge className="size-5" />
          </div>
          <h3 className="mt-5 text-sm font-semibold">Your workspace is ready</h3>
          <p className="mt-2 text-xs leading-5 text-muted-foreground">
            You are signed in to {tenantName} with your account permissions.
          </p>
        </div>
        <div className="rounded-2xl border border-border bg-card/80 p-5 shadow-lg shadow-black/5">
          <div className="grid size-10 place-items-center rounded-xl bg-amber-300/10 text-amber-300">
            <Construction className="size-5" />
          </div>
          <h3 className="mt-5 text-sm font-semibold">Operations views are next</h3>
          <p className="mt-2 text-xs leading-5 text-muted-foreground">
            This route is in place. Its fleet data and live controls are planned for the next
            phases.
          </p>
        </div>
        <div className="rounded-2xl border border-border bg-card/80 p-5 shadow-lg shadow-black/5">
          <div className="grid size-10 place-items-center rounded-xl bg-sky-300/10 text-sky-300">
            <ArrowUpRight className="size-5" />
          </div>
          <h3 className="mt-5 text-sm font-semibold">Tenant scoped by default</h3>
          <p className="mt-2 text-xs leading-5 text-muted-foreground">
            Every screen will use the tenant identity confirmed by the backend.
          </p>
        </div>
      </div>
    </section>
  )
}
