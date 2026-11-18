import type * as maplibregl from 'maplibre-gl'

const PIXEL_RATIO = 2

/** Draws a small anti-aliased dot (11 CSS px) with a dark outline, matching the dark basemap. */
function placeDot(): { width: number; height: number; data: Uint8Array } {
  const size = 11 * PIXEL_RATIO
  const data = new Uint8Array(size * size * 4)
  const center = (size - 1) / 2
  const outer = size / 2 - 0.5
  const inner = outer - 1.5 * PIXEL_RATIO
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const distance = Math.hypot(x - center, y - center)
      const alpha = Math.max(0, Math.min(1, outer - distance + 0.5))
      if (alpha === 0) continue
      const isFill = distance <= inner
      const offset = (y * size + x) * 4
      data[offset] = isFill ? 168 : 15 // fill #a8b3c7, outline #0f172a
      data[offset + 1] = isFill ? 179 : 23
      data[offset + 2] = isFill ? 199 : 42
      data[offset + 3] = Math.round(alpha * 255)
    }
  }
  return { width: size, height: size, data }
}

/**
 * OpenFreeMap's styles reference `circle-11` for town/city markers while their sprite names it
 * `circle_11`, so MapLibre logs a missing-image warning. Supply a generated dot for that icon
 * (and any other `circle-*` name) instead. Other missing icons are left unresolved.
 */
export function resolveMissingBasemapImages(map: maplibregl.Map) {
  map.setMissingStyleImageResolver((id) => {
    if (!id.startsWith('circle-') || map.hasImage(id)) return
    map.addImage(id, placeDot(), { pixelRatio: PIXEL_RATIO })
  })
}