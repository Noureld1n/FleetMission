import { create } from 'zustand'
import type { MeResponse } from '@/shared/types/contracts'

const SESSION_TOKEN_KEY = 'fleet-mission.access-token'

function readSessionToken(): string | null {
  try {
    return window.sessionStorage.getItem(SESSION_TOKEN_KEY)
  } catch {
    return null
  }
}

type AuthState = {
  accessToken: string | null
  user: MeResponse | null
  bootstrapped: boolean
  setAccessToken: (token: string) => void
  setUser: (user: MeResponse) => void
  finishBootstrap: () => void
  clearSession: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  accessToken: readSessionToken(),
  user: null,
  bootstrapped: false,
  setAccessToken: (token) => {
    try {
      window.sessionStorage.setItem(SESSION_TOKEN_KEY, token)
    } catch {
      // Keep the current tab session usable when storage is disabled.
    }
    set({ accessToken: token })
  },
  setUser: (user) => set({ user }),
  finishBootstrap: () => set({ bootstrapped: true }),
  clearSession: () => {
    try {
      window.sessionStorage.removeItem(SESSION_TOKEN_KEY)
    } catch {
      // Storage may be unavailable in privacy-restricted contexts.
    }
    set({ accessToken: null, user: null, bootstrapped: true })
  },
}))
