import { apiRequest } from '@/shared/api/http'
import {
  LoginResponseSchema,
  MeResponseSchema,
  type LoginRequest,
  type LoginResponse,
  type MeResponse,
} from '@/shared/types/contracts'

export function login(request: LoginRequest): Promise<LoginResponse> {
  return apiRequest('/auth/login', LoginResponseSchema, {
    method: 'POST',
    body: JSON.stringify(request),
    authenticated: false,
  })
}

export function getCurrentUser(): Promise<MeResponse> {
  return apiRequest('/me', MeResponseSchema)
}
