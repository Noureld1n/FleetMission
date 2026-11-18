import { useEffect, useRef, useState } from 'react'
import * as maplibregl from 'maplibre-gl'
import mapWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url'
import 'maplibre-gl/dist/maplibre-gl.css'
import { useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { MapPin } from 'lucide-react'
import { useDistricts } from '@/features/fleet/hooks/useFleetData'
import { useMapSocket } from '@/features/realtime/mapSocketContext'
import { useMapStore } from '@/features/map/store/mapStore'
import { queryKeys } from '@/shared/api/queryKeys'
import type { DistrictResponse, TelemetryVehicle } from '@/shared/types/contracts'

maplibregl.setWorkerUrl(mapWorkerUrl)

const mapStyle = import.meta.env.VITE_MAP_STYLE_URL?.trim()
const hasWebGL2 = typeof document !== 'undefined' && Boolean(document.createElement('canvas').getContext('webgl2'))
const emptyGeoJson = { type: 'FeatureCollection' as const, features: [] as Array<Record<string, unknown>> }
const initialMapError = !mapStyle ? 'Configure VITE_MAP_STYLE_URL to show the live map.' : !hasWebGL2 ? 'This device does not support WebGL2.' : ''

function vehiclesGeoJson(vehicles: TelemetryVehicle[]) {
  return {
    type: 'FeatureCollection' as const,
    features: vehicles.map((vehicle) => ({
      type: 'Feature' as const,
      id: vehicle.vehicleId,
      geometry: { type: 'Point' as const, coordinates: [vehicle.lon, vehicle.lat] },
      properties: { status: vehicle.status },
    })),
  }
}

function clustersGeoJson(clusters: Record<string, number>, zoom: number) {
  const n = 2 ** zoom
  return {
    type: 'FeatureCollection' as const,
    features: Object.entries(clusters).flatMap(([tile, count]) => {
      const [x, y] = tile.split(':').map(Number)
      if (!Number.isInteger(x) || !Number.isInteger(y)) return []
      const longitude = ((x + 0.5) / n) * 360 - 180
      const latitude = (Math.atan(Math.sinh(Math.PI * (1 - (2 * (y + 0.5)) / n))) * 180) / Math.PI
      return [{
        type: 'Feature' as const,
        id: tile,
        geometry: { type: 'Point' as const, coordinates: [longitude, latitude] },
        properties: { count },
      }]
    }),
  }
}

function districtsGeoJson(districts: DistrictResponse[] = []) {
  return {
    type: 'FeatureCollection' as const,
    features: districts.map((district) => ({
      type: 'Feature' as const,
      id: district.id,
      geometry: district.boundary,
      properties: { name: district.name },
    })),
  }
}

export function DashboardMapWidget() {
  const host = useRef<HTMLDivElement>(null)
  const mapRef = useRef<maplibregl.Map | null>(null)
  const [mapError, setMapError] = useState(initialMapError)
  const { sendViewport } = useMapSocket()
  const queryClient = useQueryClient()
  const districts = useDistricts()

  useEffect(() => {
    if (!host.current) return
    if (!mapStyle || !hasWebGL2) return
    let map: maplibregl.Map
    try {
      map = new maplibregl.Map({ container: host.current, style: mapStyle, center: [31.2357, 30.0444], zoom: 5.5 })
    } catch {
      window.setTimeout(() => setMapError('The basemap could not be started.'), 0)
      return
    }
    mapRef.current = map
    let loaded = false
    let frame: number | null = null
    const sync = () => {
      if (!loaded || frame !== null) return
      frame = requestAnimationFrame(() => {
        frame = null
        const state = useMapStore.getState()
        const vehicleSource = map.getSource('dashboard-vehicles') as maplibregl.GeoJSONSource | undefined
        const clusterSource = map.getSource('dashboard-clusters') as maplibregl.GeoJSONSource | undefined
        vehicleSource?.setData(state.dataMode === 'vehicles' ? vehiclesGeoJson(Object.values(state.vehicles)) : emptyGeoJson)
        clusterSource?.setData(state.dataMode === 'clusters' ? clustersGeoJson(state.clusters, state.viewport?.zoom ?? 0) : emptyGeoJson)
      })
    }
    const unsubscribe = useMapStore.subscribe(sync)
    map.addControl(new maplibregl.NavigationControl({ showCompass: true }), 'top-right')
    map.on('load', () => {
      loaded = true
      map.addSource('dashboard-vehicles', { type: 'geojson', data: emptyGeoJson })
      map.addLayer({ id: 'dashboard-vehicle-points', type: 'circle', source: 'dashboard-vehicles', paint: {
        'circle-radius': 5,
        'circle-color': ['match', ['get', 'status'], 'active', '#4ade80', 'charging', '#38bdf8', 'offline', '#fbbf24', 'fault', '#fb7185', '#a8b3c7'],
        'circle-stroke-color': '#101827', 'circle-stroke-width': 1.5,
      } })
      map.addSource('dashboard-clusters', { type: 'geojson', data: emptyGeoJson })
      map.addLayer({ id: 'dashboard-cluster-points', type: 'circle', source: 'dashboard-clusters', paint: {
        'circle-radius': ['interpolate', ['linear'], ['get', 'count'], 1, 12, 100, 26, 1000, 38],
        'circle-color': '#0ea5e9', 'circle-opacity': 0.85, 'circle-stroke-color': '#e0f2fe', 'circle-stroke-width': 1.5,
      } })
      map.addLayer({ id: 'dashboard-cluster-labels', type: 'symbol', source: 'dashboard-clusters', layout: { 'text-field': ['to-string', ['get', 'count']], 'text-size': 10 }, paint: { 'text-color': '#062033' } })
      const initialDistricts = queryClient.getQueryData<{ items: DistrictResponse[] }>(queryKeys.districts())?.items
      map.addSource('dashboard-districts', { type: 'geojson', data: districtsGeoJson(initialDistricts) })
      map.addLayer({ id: 'dashboard-district-fill', type: 'fill', source: 'dashboard-districts', paint: { 'fill-color': '#22d3ee', 'fill-opacity': 0.08 } })
      map.addLayer({ id: 'dashboard-district-line', type: 'line', source: 'dashboard-districts', paint: { 'line-color': '#67e8f9', 'line-width': 1.25 } })
      map.on('moveend', () => sendViewportForMap(map, sendViewport))
      map.on('zoomend', () => sendViewportForMap(map, sendViewport))
      sendViewportForMap(map, sendViewport)
      sync()
    })
    const resizeObserver = new ResizeObserver(() => map.resize())
    resizeObserver.observe(host.current)
    map.on('error', (event) => {
      if (!map.isStyleLoaded()) setMapError(event.error?.message ?? 'The map style could not be loaded.')
    })
    return () => {
      loaded = false
      unsubscribe()
      resizeObserver.disconnect()
      if (frame !== null) cancelAnimationFrame(frame)
      map.remove()
      mapRef.current = null
    }
  }, [queryClient, sendViewport])

  useEffect(() => {
    const source = mapRef.current?.getSource('dashboard-districts') as maplibregl.GeoJSONSource | undefined
    source?.setData(districtsGeoJson(districts.data?.items))
  }, [districts.data])

  if (mapError) return <div className="grid min-h-52 place-items-center rounded-xl bg-slate-950 p-5 text-center text-xs text-slate-300"><div><MapPin className="mx-auto size-5 text-primary" /><p className="mt-2">{mapError}</p><Link className="mt-2 inline-block text-primary hover:underline" to="/map">Open map page</Link></div></div>
  return <div ref={host} className="dashboard-widget-map h-full min-h-52 overflow-hidden rounded-xl" aria-label="Interactive fleet map widget" />
}

function sendViewportForMap(map: maplibregl.Map, send: (viewport: { west: number; south: number; east: number; north: number; zoom: number }) => void) {
  const bounds = map.getBounds()
  send({ west: bounds.getWest(), south: bounds.getSouth(), east: bounds.getEast(), north: bounds.getNorth(), zoom: Math.max(0, Math.min(22, Math.floor(map.getZoom()))) })
}
