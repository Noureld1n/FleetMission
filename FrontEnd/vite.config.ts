import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const backendTarget = env.BACKEND_PROXY_TARGET || 'http://localhost:8080'
  const managementTarget = env.MANAGEMENT_PROXY_TARGET || 'http://127.0.0.1:8081'
  const previewPort = 4173
  // Allow the map style's host (style, tiles, glyphs, sprites) without hard-coding a provider.
  // A relative style URL (e.g. /map-style.json in public/) is already covered by 'self'.
  const mapStyleOrigin = /^https?:\/\//.test(env.VITE_MAP_STYLE_URL ?? '')
    ? new URL(env.VITE_MAP_STYLE_URL).origin
    : ''
  const contentSecurityPolicy = [
    "default-src 'self'",
    "script-src 'self'",
    "worker-src 'self' blob:",
    "style-src 'self'",
    "img-src 'self' data: blob:",
    "font-src 'self'",
    `connect-src 'self' ws://localhost:${previewPort} ${mapStyleOrigin}`.trim(),
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join('; ')

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      tsconfigPaths: true,
    },
    server: {
      proxy: {
        '/api': { target: backendTarget, changeOrigin: true },
        '/ingest': { target: backendTarget, changeOrigin: true },
        '/actuator': { target: managementTarget, changeOrigin: true },
        '/ws': { target: backendTarget, changeOrigin: true, ws: true },
      },
    },
    // Production-like CSP for `npm run build && npm run preview`. Not applied to `npm run dev`,
    // whose HMR needs inline scripts/styles. preview.proxy inherits server.proxy.
    preview: {
      port: previewPort,
      strictPort: true,
      headers: { 'Content-Security-Policy': contentSecurityPolicy },
    },
  }
})