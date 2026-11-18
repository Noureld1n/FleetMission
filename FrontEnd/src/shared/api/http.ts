import { useAuthStore } from '@/features/auth/store/authStore'
import { ProblemDetailsSchema } from '@/shared/types/contracts'
import type { z } from 'zod'

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')

export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  readonly problem?: z.infer<typeof ProblemDetailsSchema>

  constructor(status: number, problem?: z.infer<typeof ProblemDetailsSchema>) {
    super(problem?.detail ?? problem?.title ?? `Request failed with status ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.code = problem?.code
    this.problem = problem
  }
}

type RequestOptions = RequestInit & { authenticated?: boolean }

export async function apiRequest<T>(
  path: string,
  schema: z.ZodType<T>,
  options: RequestOptions = {},
): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const token = useAuthStore.getState().accessToken
  if (options.authenticated !== false && token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const requestOptions = { ...options }
  delete requestOptions.authenticated
  const route = path.startsWith('/') ? path : `/${path}`
  const response = await fetch(`${API_BASE_URL}${route}`, {
    ...requestOptions,
    headers,
  })

  if (response.status === 401) {
    const hadSession = Boolean(useAuthStore.getState().accessToken)
    useAuthStore.getState().clearSession()
    if (hadSession) window.dispatchEvent(new Event('fleet:unauthorized'))
  }

  if (!response.ok) {
    let problem: z.infer<typeof ProblemDetailsSchema> | undefined
    try {
      problem = ProblemDetailsSchema.parse(await response.json())
    } catch {
      problem = undefined
    }
    throw new ApiError(response.status, problem)
  }

  if (response.status === 204) {
    return undefined as T
  }

  return schema.parse(await response.json())
}
