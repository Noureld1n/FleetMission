import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { MapPinned, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useAuthStore } from '@/features/auth/store/authStore'
import { useCreateDistrict, useDistricts } from '@/features/fleet/hooks/useFleetData'
import { useToast } from '@/shared/ui/useToast'
import { EmptyState, ErrorState, PageSkeleton } from '@/shared/ui/Feedback'

type Coordinate = { longitude: string; latitude: string }
const blankCoordinate = (): Coordinate => ({ longitude: '', latitude: '' })

export function DistrictsPage() {
  const role = useAuthStore((state) => state.user?.role)
  const query = useDistricts()
  const createDistrict = useCreateDistrict()
  const toast = useToast()
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [coordinates, setCoordinates] = useState<Coordinate[]>([
    blankCoordinate(),
    blankCoordinate(),
    blankCoordinate(),
  ])
  const [error, setError] = useState('')

  function updateCoordinate(index: number, key: keyof Coordinate, value: string) {
    setCoordinates((current) =>
      current.map((coordinate, itemIndex) =>
        itemIndex === index ? { ...coordinate, [key]: value } : coordinate,
      ),
    )
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const points = coordinates.map(({ longitude, latitude }) => ({
      longitude: Number(longitude),
      latitude: Number(latitude),
    }))
    if (
      !name.trim() ||
      points.length < 3 ||
      points.some(
        (point, index) =>
          !coordinates[index].longitude.trim() ||
          !coordinates[index].latitude.trim() ||
          !Number.isFinite(point.longitude) ||
          !Number.isFinite(point.latitude) ||
          point.longitude < -180 ||
          point.longitude > 180 ||
          point.latitude < -90 ||
          point.latitude > 90,
      )
    ) {
      setError('Enter a name and at least three valid longitude/latitude points.')
      return
    }
    const ring = [...points, points[0]]
    setError('')
    try {
      const created = await createDistrict.mutateAsync({
        name: name.trim(),
        boundary: { type: 'Polygon', coordinates: [ring] },
      })
      setCreating(false)
      setName('')
      setCoordinates([blankCoordinate(), blankCoordinate(), blankCoordinate()])
      toast({ kind: 'success', title: 'District created', description: created.name })
    } catch {
      setError('District could not be created. Check the boundary and try again.')
    }
  }

  if (query.isPending) return <PageSkeleton rows={3} />
  if (query.isError)
    return (
      <ErrorState
        description="Districts could not be loaded."
        onRetry={() => void query.refetch()}
      />
    )

  return (
    <section className="mx-auto max-w-6xl">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="text-xs font-semibold tracking-[0.18em] text-primary uppercase">
            Service areas
          </p>
          <h2 className="mt-2 text-3xl font-semibold tracking-tight sm:text-4xl">Districts</h2>
          <p className="mt-2 text-sm text-muted-foreground">
            Review tenant service boundaries and their assigned vehicles.
          </p>
        </div>
        {role === 'operator' && (
          <Button onClick={() => setCreating((value) => !value)}>
            <Plus className="size-4" />
            New district
          </Button>
        )}
      </div>

      {creating && role === 'operator' && (
        <form
          onSubmit={submit}
          className="mt-7 space-y-4 rounded-2xl border border-primary/20 bg-primary/[0.035] p-5"
        >
          <div className="grid gap-2 sm:max-w-md">
            <label htmlFor="district-name" className="text-sm font-medium">
              District name
            </label>
            <input
              id="district-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              maxLength={120}
              required
              className="h-10 rounded-xl border border-input bg-card px-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
              placeholder="North operations zone"
            />
          </div>
          <div>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <h3 className="text-sm font-semibold">Boundary points</h3>
                <p className="mt-1 text-xs text-muted-foreground">
                  Enter at least three vertices. The first point is repeated automatically to close
                  the polygon.
                </p>
              </div>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setCoordinates((current) => [...current, blankCoordinate()])}
              >
                Add point
              </Button>
            </div>
            <div className="mt-3 grid gap-2">
              {coordinates.map((point, index) => (
                <div key={index} className="grid gap-2 sm:grid-cols-[auto_1fr_1fr]">
                  <span className="self-center text-xs text-muted-foreground">
                    Point {index + 1}
                  </span>
                  <input
                    aria-label={`Point ${index + 1} longitude`}
                    type="number"
                    step="any"
                    min="-180"
                    max="180"
                    value={point.longitude}
                    onChange={(event) => updateCoordinate(index, 'longitude', event.target.value)}
                    placeholder="Longitude"
                    className="h-10 rounded-xl border border-input bg-card px-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  />
                  <input
                    aria-label={`Point ${index + 1} latitude`}
                    type="number"
                    step="any"
                    min="-90"
                    max="90"
                    value={point.latitude}
                    onChange={(event) => updateCoordinate(index, 'latitude', event.target.value)}
                    placeholder="Latitude"
                    className="h-10 rounded-xl border border-input bg-card px-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  />
                </div>
              ))}
            </div>
          </div>
          {error && (
            <p role="alert" className="text-xs text-destructive">
              {error}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                setCreating(false)
                setError('')
              }}
            >
              Cancel
            </Button>
            <Button type="submit" disabled={createDistrict.isPending}>
              {createDistrict.isPending ? 'Creating…' : 'Create district'}
            </Button>
          </div>
        </form>
      )}

      {query.data.items.length === 0 ? (
        <div className="mt-8">
          <EmptyState
            title="No districts yet"
            description={
              role === 'operator'
                ? 'Create a service area to organize the fleet.'
                : 'Districts assigned to this tenant will appear here.'
            }
            action={
              role === 'operator' ? (
                <Button onClick={() => setCreating(true)}>
                  <Plus className="size-4" />
                  Create district
                </Button>
              ) : undefined
            }
          />
        </div>
      ) : (
        <div className="mt-8 grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {query.data.items.map((district) => (
            <article key={district.id} className="rounded-2xl border border-border bg-card/70 p-5">
              <div className="flex items-start gap-3">
                <div className="grid size-10 place-items-center rounded-xl bg-primary/10 text-primary">
                  <MapPinned className="size-5" />
                </div>
                <div className="min-w-0">
                  <h3 className="truncate text-base font-semibold">{district.name}</h3>
                  <p className="mt-1 text-[10px] text-muted-foreground">
                    Added {new Date(district.createdAt).toLocaleDateString()}
                  </p>
                </div>
              </div>
              <p className="mt-5 text-xs text-muted-foreground">
                {Math.max(0, (district.boundary.coordinates[0]?.length ?? 1) - 1)} boundary vertices
              </p>
              <Link
                to={`/vehicles?district=${district.id}`}
                className="mt-4 inline-flex text-xs font-medium text-primary hover:underline"
              >
                Browse assigned vehicles →
              </Link>
            </article>
          ))}
        </div>
      )}
    </section>
  )
}
