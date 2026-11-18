import { useEffect, useRef, useState } from 'react'
import * as maplibregl from 'maplibre-gl'
import mapWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url'
import 'maplibre-gl/dist/maplibre-gl.css'
import { Link } from 'react-router'
import { Activity, Battery, LocateFixed, MapPin, Radio, Siren, Wifi, WifiOff } from 'lucide-react'
import { useAuthStore } from '@/features/auth/store/authStore'
import { useDistricts } from '@/features/fleet/hooks/useFleetData'
import { useMapSocket } from '@/features/realtime/mapSocketContext'
import { useRealtimeStore } from '@/features/realtime/store/realtimeStore'
import { useMapStore } from '@/features/map/store/mapStore'
import type { DistrictResponse, TelemetryVehicle, VehicleStatus } from '@/shared/types/contracts'

maplibregl.setWorkerUrl(mapWorkerUrl)

const styleUrl = import.meta.env.VITE_MAP_STYLE_URL?.trim()
const hasWebGL2 =
  typeof document !== 'undefined' && Boolean(document.createElement('canvas').getContext('webgl2'))
const missingStyleMessage = 'Set VITE_MAP_STYLE_URL in FrontEnd/.env to load the vector basemap.'
const missingWebGLMessage =
  'This browser or device does not support WebGL2, so the live map cannot be displayed.'
const emptyCollection = {
  type: 'FeatureCollection' as const,
  features: [] as Array<{
    type: 'Feature'
    id: string | number
    geometry: { type: 'Point'; coordinates: [number, number] }
    properties: Record<string, string | number>
  }>,
}
const vehicleColors: Record<VehicleStatus, string> = {
  active: '#4ade80',
  idle: '#a8b3c7',
  charging: '#38bdf8',
  offline: '#fbbf24',
  fault: '#fb7185',
}

function districtGeoJson(districts: DistrictResponse[] = []) {
  return {
    type: 'FeatureCollection' as const,
    features: districts.map((district) => ({
      type: 'Feature' as const,
      id: district.id,
      geometry: district.boundary,
      properties: { id: district.id, name: district.name },
    })),
  }
}

function filterVehicles(
  vehicles: TelemetryVehicle[],
  statusFilter: VehicleStatus | 'all',
  districtFilter: string,
  districts: DistrictResponse[],
) {
  const ring =
    districtFilter === 'all'
      ? undefined
      : (districts.find((item) => item.id === districtFilter)?.boundary.coordinates[0] as
          number[][] | undefined)
  return vehicles.filter((vehicle) => {
    if (statusFilter !== 'all' && vehicle.status !== statusFilter) return false
    if (districtFilter === 'all') return true
    return Boolean(ring && pointInRing(vehicle.lon, vehicle.lat, ring))
  })
}

function pointInRing(longitude: number, latitude: number, ring: number[][]) {
  let inside = false
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i]
    const [xj, yj] = ring[j]
    const intersects =
      yi > latitude !== yj > latitude &&
      longitude < ((xj - xi) * (latitude - yi)) / (yj - yi || Number.EPSILON) + xi
    if (intersects) inside = !inside
  }
  return inside
}

function tileCenter(key: string, zoom: number): [number, number] | null {
  const [xText, yText] = key.split(':')
  const x = Number(xText)
  const y = Number(yText)
  if (!Number.isInteger(x) || !Number.isInteger(y)) return null
  const n = 2 ** zoom
  const longitude = ((x + 0.5) / n) * 360 - 180
  const latitude = (Math.atan(Math.sinh(Math.PI * (1 - (2 * (y + 0.5)) / n))) * 180) / Math.PI
  return [longitude, latitude]
}

function toVehicleCollection(vehicles: TelemetryVehicle[], selectedId: string | null) {
  return {
    type: 'FeatureCollection' as const,
    features: vehicles.map((vehicle) => ({
      type: 'Feature' as const,
      id: vehicle.vehicleId,
      geometry: {
        type: 'Point' as const,
        coordinates: [vehicle.lon, vehicle.lat] as [number, number],
      },
      properties: {
        vehicleId: vehicle.vehicleId,
        status: vehicle.status,
        selected: vehicle.vehicleId === selectedId ? 1 : 0,
      },
    })),
  }
}

function toClusterCollection(clusters: Record<string, number>, zoom: number) {
  return {
    type: 'FeatureCollection' as const,
    features: Object.entries(clusters).flatMap(([key, count]) => {
      const center = tileCenter(key, zoom)
      return center
        ? [
            {
              type: 'Feature' as const,
              id: key,
              geometry: { type: 'Point' as const, coordinates: center },
              properties: { count },
            },
          ]
        : []
    }),
  }
}

function sendViewportFromMap(
  map: maplibregl.Map,
  sendViewport: (value: {
    west: number
    south: number
    east: number
    north: number
    zoom: number
  }) => void,
) {
  const bounds = map.getBounds()
  sendViewport({
    west: bounds.getWest(),
    south: bounds.getSouth(),
    east: bounds.getEast(),
    north: bounds.getNorth(),
    zoom: Math.max(0, Math.min(22, Math.floor(map.getZoom()))),
  })
}

export default function MapRoute() {
  const container = useRef<HTMLDivElement>(null)
  const mapRef = useRef<maplibregl.Map | null>(null)
  const syncMapDataRef = useRef<(() => void) | null>(null)
  const [mapError, setMapError] = useState(
    !styleUrl ? missingStyleMessage : !hasWebGL2 ? missingWebGLMessage : '',
  )
  const [statusFilter, setStatusFilter] = useState<VehicleStatus | 'all'>('all')
  const [districtFilter, setDistrictFilter] = useState('all')
  const { sendViewport } = useMapSocket()
  const districts = useDistricts()
  const dataMode = useMapStore((state) => state.dataMode)
  const selectedId = useMapStore((state) => state.selectedVehicleId)
  const setSelected = useMapStore((state) => state.setSelectedVehicle)
  const selectedVehicle = useMapStore((state) =>
    state.selectedVehicleId ? state.vehicles[state.selectedVehicleId] : undefined,
  )
  const lastError = useMapStore((state) => state.lastError)
  const clearError = useMapStore((state) => state.setLastError)
  const mapStatus = useRealtimeStore((state) => state.mapStatus)
  const role = useAuthStore((state) => state.user?.role)
  const [visibleUnitCount, setVisibleUnitCount] = useState(0)
  const filtersRef = useRef({
    statusFilter,
    districtFilter,
    districts: districts.data?.items ?? [],
    selectedId,
  })

  useEffect(() => {
    filtersRef.current = {
      statusFilter,
      districtFilter,
      districts: districts.data?.items ?? [],
      selectedId,
    }
    syncMapDataRef.current?.()
  }, [statusFilter, districtFilter, districts.data?.items, selectedId])

  useEffect(() => {
    const interval = window.setInterval(() => {
      const state = useMapStore.getState()
      const count =
        state.dataMode === 'clusters'
          ? Object.values(state.clusters).reduce((sum, value) => sum + value, 0)
          : filterVehicles(
              Object.values(state.vehicles),
              statusFilter,
              districtFilter,
              districts.data?.items ?? [],
            ).length
      setVisibleUnitCount((current) => (current === count ? current : count))
    }, 1000)
    return () => window.clearInterval(interval)
  }, [statusFilter, districtFilter, districts.data?.items])

  useEffect(() => {
    if (!container.current) return
    if (!styleUrl || !hasWebGL2) return
    let map: maplibregl.Map
    try {
      map = new maplibregl.Map({
        container: container.current,
        style: styleUrl,
        center: [31.2357, 30.0444],
        zoom: 5.5,
      })
    } catch {
      window.setTimeout(
        () =>
          setMapError('The map could not start. Check the style URL and browser WebGL support.'),
        0,
      )
      return
    }
    mapRef.current = map
    let styleLoaded = false
    let frameId: number | null = null
    let lastDistricts: DistrictResponse[] | null = null
    const syncMapData = () => {
      if (!styleLoaded || frameId !== null) return
      frameId = window.requestAnimationFrame(() => {
        frameId = null
        const state = useMapStore.getState()
        const filters = filtersRef.current
        const vehiclesSource = map.getSource('fleet-vehicles') as
          maplibregl.GeoJSONSource | undefined
        const clustersSource = map.getSource('fleet-clusters') as
          maplibregl.GeoJSONSource | undefined
        const districtsSource = map.getSource('fleet-districts') as
          maplibregl.GeoJSONSource | undefined
        if (vehiclesSource) {
          const filtered =
            state.dataMode === 'vehicles'
              ? filterVehicles(
                  Object.values(state.vehicles),
                  filters.statusFilter,
                  filters.districtFilter,
                  filters.districts,
                )
              : []
          vehiclesSource.setData(toVehicleCollection(filtered, filters.selectedId))
        }
        if (clustersSource) {
          clustersSource.setData(
            state.dataMode === 'clusters'
              ? toClusterCollection(state.clusters, state.viewport?.zoom ?? 0)
              : emptyCollection,
          )
        }
        if (districtsSource && lastDistricts !== filters.districts) {
          districtsSource.setData(districtGeoJson(filters.districts))
          lastDistricts = filters.districts
        }
      })
    }
    syncMapDataRef.current = syncMapData
    const unsubscribeMapStore = useMapStore.subscribe(syncMapData)
    map.addControl(new maplibregl.NavigationControl({ showCompass: true }), 'top-right')
    map.on('load', () => {
      styleLoaded = true
      map.addSource('fleet-vehicles', { type: 'geojson', data: emptyCollection })
      map.addLayer({
        id: 'fleet-vehicle-circles',
        type: 'circle',
        source: 'fleet-vehicles',
        paint: {
          'circle-radius': ['case', ['==', ['get', 'selected'], 1], 9, 6],
          'circle-color': [
            'match',
            ['get', 'status'],
            'active',
            vehicleColors.active,
            'idle',
            vehicleColors.idle,
            'charging',
            vehicleColors.charging,
            'offline',
            vehicleColors.offline,
            'fault',
            vehicleColors.fault,
            '#a8b3c7',
          ],
          'circle-stroke-color': ['case', ['==', ['get', 'selected'], 1], '#ffffff', '#101827'],
          'circle-stroke-width': ['case', ['==', ['get', 'selected'], 1], 3, 1.5],
        },
      })
      map.addSource('fleet-clusters', { type: 'geojson', data: emptyCollection })
      map.addLayer({
        id: 'fleet-cluster-circles',
        type: 'circle',
        source: 'fleet-clusters',
        paint: {
          'circle-radius': ['interpolate', ['linear'], ['get', 'count'], 1, 13, 100, 28, 1000, 42],
          'circle-color': '#0ea5e9',
          'circle-opacity': 0.82,
          'circle-stroke-color': '#e0f2fe',
          'circle-stroke-width': 1.5,
        },
      })
      map.addLayer({
        id: 'fleet-cluster-labels',
        type: 'symbol',
        source: 'fleet-clusters',
        layout: {
          'text-field': ['to-string', ['get', 'count']],
          'text-size': 11,
          'text-allow-overlap': true,
        },
        paint: { 'text-color': '#062033' },
      })
      map.addSource('fleet-districts', { type: 'geojson', data: districtGeoJson() })
      map.addLayer({
        id: 'fleet-district-fill',
        type: 'fill',
        source: 'fleet-districts',
        paint: { 'fill-color': '#22d3ee', 'fill-opacity': 0.08 },
      })
      map.addLayer({
        id: 'fleet-district-outline',
        type: 'line',
        source: 'fleet-districts',
        paint: { 'line-color': '#67e8f9', 'line-width': 1.5, 'line-opacity': 0.8 },
      })
      map.on('click', 'fleet-vehicle-circles', (event) => {
        const id = event.features?.[0]?.properties?.vehicleId
        if (typeof id === 'string') setSelected(id)
      })
      map.on('mouseenter', 'fleet-vehicle-circles', () => {
        map.getCanvas().style.cursor = 'pointer'
      })
      map.on('mouseleave', 'fleet-vehicle-circles', () => {
        map.getCanvas().style.cursor = ''
      })
      map.on('moveend', () => sendViewportFromMap(map, sendViewport))
      map.on('zoomend', () => sendViewportFromMap(map, sendViewport))
      syncMapData()
      sendViewportFromMap(map, sendViewport)
      setMapError('')
    })
    map.on('error', (event) => {
      if (!map.isStyleLoaded())
        setMapError(event.error?.message || 'The map style could not be loaded.')
    })
    return () => {
      styleLoaded = false
      unsubscribeMapStore()
      syncMapDataRef.current = null
      if (frameId !== null) window.cancelAnimationFrame(frameId)
      map.remove()
      mapRef.current = null
    }
  }, [sendViewport, setSelected])

  useEffect(() => {
    if (!selectedVehicle || !mapRef.current) return
    mapRef.current.flyTo({
      center: [selectedVehicle.lon, selectedVehicle.lat],
      zoom: Math.max(mapRef.current.getZoom(), 11),
      duration: 650,
    })
  }, [selectedVehicle])

  const mapConnectionIcon =
    mapStatus === 'live'
      ? Wifi
      : mapStatus === 'reconnecting' || mapStatus === 'connecting' || mapStatus === 'authenticating'
        ? Radio
        : WifiOff
  const MapIcon = mapConnectionIcon

  return (
    <section className="mx-auto max-w-[1500px]">
      <div className="mb-5 flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="text-xs font-semibold tracking-[0.18em] text-primary uppercase">
            Live operations
          </p>
          <h2 className="mt-2 text-3xl font-semibold tracking-tight sm:text-4xl">Fleet map</h2>
          <p className="mt-2 text-sm text-muted-foreground">
            Tenant-scoped positions, service areas, and live connection state.
          </p>
        </div>
        <div className="inline-flex items-center gap-2 rounded-full border border-border bg-card px-3 py-2 text-xs text-muted-foreground">
          <MapIcon
            className={`size-3.5 ${mapStatus === 'live' ? 'text-emerald-400' : 'text-amber-300'}`}
          />
          Map {mapStatus}
        </div>
      </div>
      {lastError && (
        <div
          role="alert"
          className="mb-4 flex items-start justify-between gap-4 rounded-xl border border-amber-300/20 bg-amber-300/[0.06] p-3 text-xs text-amber-100"
        >
          <span>{lastError}</span>
          <button
            type="button"
            onClick={() => clearError(null)}
            className="underline underline-offset-2"
          >
            Dismiss
          </button>
        </div>
      )}
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_320px]">
        <div className="relative min-h-[480px] overflow-hidden rounded-2xl border border-border bg-slate-950 sm:min-h-[620px]">
          <div ref={container} className="absolute inset-0" aria-label="Live fleet map" />
          {mapError && (
            <div className="absolute inset-0 z-10 grid place-items-center bg-background/95 p-7 text-center">
              <div className="max-w-md">
                <MapPin className="mx-auto size-8 text-primary" />
                <h3 className="mt-4 text-lg font-semibold">Map unavailable</h3>
                <p className="mt-2 text-sm leading-6 text-muted-foreground">{mapError}</p>
                <p className="mt-3 text-xs text-muted-foreground">
                  Vehicle, district, and history pages remain available.
                </p>
              </div>
            </div>
          )}
          {!mapError && (
            <button
              type="button"
              onClick={() => mapRef.current?.flyTo({ center: [31.2357, 30.0444], zoom: 5.5 })}
              className="absolute top-3 left-3 z-10 inline-flex items-center gap-2 rounded-lg border border-white/15 bg-slate-950/80 px-3 py-2 text-xs text-white shadow-lg backdrop-blur"
            >
              <LocateFixed className="size-3.5" />
              Reset view
            </button>
          )}
        </div>
        <aside className="flex flex-col gap-4 rounded-2xl border border-border bg-card/60 p-4">
          <div>
            <div className="flex items-center gap-2">
              <Activity className="size-4 text-primary" />
              <h3 className="text-sm font-semibold">Map controls</h3>
            </div>
            <label className="mt-4 grid gap-1.5 text-[11px] font-medium text-muted-foreground">
              Vehicle status
              <select
                value={statusFilter}
                onChange={(event) => setStatusFilter(event.target.value as VehicleStatus | 'all')}
                className="h-10 rounded-xl border border-input bg-background px-3 text-sm text-foreground"
              >
                <option value="all">All statuses</option>
                {Object.keys(vehicleColors).map((status) => (
                  <option key={status} value={status}>
                    {status}
                  </option>
                ))}
              </select>
            </label>
            <label className="mt-3 grid gap-1.5 text-[11px] font-medium text-muted-foreground">
              District boundary
              <select
                value={districtFilter}
                onChange={(event) => setDistrictFilter(event.target.value)}
                className="h-10 rounded-xl border border-input bg-background px-3 text-sm text-foreground"
              >
                <option value="all">All districts</option>
                {districts.data?.items.map((district) => (
                  <option key={district.id} value={district.id}>
                    {district.name}
                  </option>
                ))}
              </select>
            </label>
            {districts.isError && (
              <p className="mt-2 text-xs text-amber-300">District overlays are unavailable.</p>
            )}
          </div>
          {role === 'operator' && (
            <div className="rounded-xl border border-amber-300/15 bg-amber-300/[0.04] p-3">
              <div className="flex items-center gap-2 text-xs font-semibold text-amber-100">
                <Siren className="size-3.5" />
                Scenario actions
              </div>
              <button
                type="button"
                disabled
                title="Outage scenario controls are scheduled for Phase 6"
                className="mt-3 w-full rounded-lg border border-border px-3 py-2 text-xs text-muted-foreground opacity-60"
              >
                Trigger district outage · coming later
              </button>
            </div>
          )}
          <div className="border-t border-border pt-4">
            <h3 className="text-xs font-semibold text-muted-foreground">Visible units</h3>
            <p className="mt-1 text-2xl font-semibold">{visibleUnitCount}</p>
            <p className="text-[10px] text-muted-foreground">
              {dataMode === 'clusters'
                ? 'vehicles represented by map clusters'
                : `live in current view · ${mapStatus}`}
            </p>
            {dataMode === 'clusters' && (statusFilter !== 'all' || districtFilter !== 'all') && (
              <p className="mt-2 text-[10px] leading-4 text-amber-200/80">
                Zoom in to apply status and district filters to individual vehicles.
              </p>
            )}
          </div>
          <div className="border-t border-border pt-4">
            {selectedVehicle ? (
              <>
                <div className="flex items-center justify-between gap-2">
                  <h3 className="text-sm font-semibold">Selected vehicle</h3>
                  <button
                    type="button"
                    onClick={() => setSelected(null)}
                    className="text-[10px] text-muted-foreground underline"
                  >
                    Clear
                  </button>
                </div>
                <p className="mt-3 break-all font-mono text-xs text-primary">
                  {selectedVehicle.vehicleId}
                </p>
                <div className="mt-3 flex flex-wrap gap-2">
                  <span className="rounded-full border border-border px-2 py-1 text-[10px] capitalize">
                    {selectedVehicle.status}
                  </span>
                  <span className="rounded-full border border-border px-2 py-1 text-[10px]">
                    Seq {selectedVehicle.seq}
                  </span>
                </div>
                <div className="mt-3 flex items-center gap-2 text-xs text-muted-foreground">
                  <Battery className="size-3.5" />
                  {selectedVehicle.batteryPct}%
                </div>
                <p className="mt-2 text-[10px] text-muted-foreground">
                  Updated {new Date(selectedVehicle.ts).toLocaleString()}
                </p>
                <Link
                  to={`/vehicles/${selectedVehicle.vehicleId}`}
                  className="mt-4 inline-block text-xs font-medium text-primary hover:underline"
                >
                  Open vehicle profile →
                </Link>
              </>
            ) : (
              <>
                <h3 className="text-sm font-semibold">Vehicle details</h3>
                <p className="mt-2 text-xs leading-5 text-muted-foreground">
                  Select a vehicle on the map to inspect its current position, status, and telemetry
                  sequence.
                </p>
              </>
            )}
          </div>
          <div className="mt-auto border-t border-border pt-4">
            <h3 className="text-[10px] font-semibold tracking-[0.14em] text-muted-foreground uppercase">
              Status legend
            </h3>
            <div className="mt-3 grid grid-cols-2 gap-2">
              {Object.entries(vehicleColors).map(([status, color]) => (
                <div
                  key={status}
                  className="flex items-center gap-2 text-[10px] capitalize text-muted-foreground"
                >
                  <span className="size-2 rounded-full" style={{ backgroundColor: color }} />
                  {status}
                </div>
              ))}
            </div>
          </div>
        </aside>
      </div>
    </section>
  )
}
