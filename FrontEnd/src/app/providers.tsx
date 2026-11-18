import { useEffect, useRef, type ReactNode } from 'react'
import { QueryClientProvider } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { queryClient } from '@/app/queryClient'
import { getCurrentUser } from '@/features/auth/api/authApi'
import { useAuthStore } from '@/features/auth/store/authStore'
import { ToastProvider } from '@/shared/ui/Feedback'

function AuthBootstrap() {
  const accessToken = useAuthStore((state) => state.accessToken)
  const setUser = useAuthStore((state) => state.setUser)
  const clearSession = useAuthStore((state) => state.clearSession)
  const finishBootstrap = useAuthStore((state) => state.finishBootstrap)
  const checked = useRef(false)

  useEffect(() => {
    if (checked.current) return
    checked.current = true
    if (!accessToken) {
      finishBootstrap()
      return
    }
    void getCurrentUser()
      .then(setUser)
      .catch(() => clearSession())
      .finally(finishBootstrap)
  }, [accessToken, clearSession, finishBootstrap, setUser])

  return null
}

function UnauthorizedRedirect() {
  const navigate = useNavigate()

  useEffect(() => {
    const handleUnauthorized = () => {
      queryClient.clear()
      void navigate('/login', { replace: true, state: { sessionExpired: true } })
    }
    window.addEventListener('fleet:unauthorized', handleUnauthorized)
    return () => window.removeEventListener('fleet:unauthorized', handleUnauthorized)
  }, [navigate])

  return null
}

export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <AuthBootstrap />
        {children}
      </ToastProvider>
    </QueryClientProvider>
  )
}

export { UnauthorizedRedirect }
