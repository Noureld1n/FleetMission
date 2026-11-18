import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const backendTarget = env.BACKEND_PROXY_TARGET || 'http://localhost:8080'
  const managementTarget = env.MANAGEMENT_PROXY_TARGET || 'http://127.0.0.1:8081'

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
  }
})
