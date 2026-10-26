import { http } from './request'

export interface UserProfile {
  id: number
  username: string
  email: string | null
  avatarUrl: string | null
  bio: string | null
  githubUrl: string | null
  roles: string[]
  createdAt: string
}

export interface UpdateProfilePayload {
  bio?: string
  githubUrl?: string
}

export interface ChangePasswordPayload {
  oldPassword: string
  newPassword: string
}

export interface HeatmapDay {
  date: string
  count: number
}

export interface HeatmapData {
  days: HeatmapDay[]
  totalAc: number
  maxStreak: number
  currentStreak: number
}

export interface UserExamRecord {
  examId: number
  title: string
  description: string | null
  mode: 'ACM' | 'OI'
  durationMinutes: number
  score: number | null
  rank: number | null
  totalParticipants: number
  submittedAt: string | null
}

export const profileApi = {
  getProfile: (userId?: number): Promise<UserProfile> =>
    http.get('/users/profile', { params: userId ? { userId } : undefined }),

  updateProfile: (data: UpdateProfilePayload): Promise<UserProfile> =>
    http.put('/users/profile', data),

  uploadAvatar: (file: File): Promise<{ avatarUrl: string }> => {
    const formData = new FormData()
    formData.append('file', file)
    return http.post('/users/avatar', formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    })
  },

  changePassword: (data: ChangePasswordPayload): Promise<void> =>
    http.put('/users/password', data),

  getHeatmap: (userId?: number): Promise<HeatmapData> =>
    http.get('/users/heatmap', { params: userId ? { userId } : undefined }),

  getExams: (userId?: number): Promise<UserExamRecord[]> =>
    http.get('/users/exams', { params: userId ? { userId } : undefined }),
}

