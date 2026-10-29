import { http } from './request'
import type { AuthResult, UserInfo } from '@/types'

export interface RegisterPayload {
  email: string
  password: string
  username?: string
}

export interface LoginPayload {
  email: string
  password: string
  username?: string
}

export const authApi = {
  register: (payload: RegisterPayload) => http.post<AuthResult>('/auth/register', payload),
  login: (payload: LoginPayload) => http.post<AuthResult>('/auth/login', payload),
  refresh: (refreshToken: string) => http.post<AuthResult>('/auth/refresh', { refreshToken }),
  logout: () => http.post<void>('/auth/logout'),
  me: () => http.get<UserInfo>('/auth/me'),
}
