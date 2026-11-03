import { http } from './request';
import type { DbQuestion, ObjectiveQuestion, PageResult, Question } from '@/types';
export interface AdminUser {
  id: number;
  username: string;
  email: string | null;
  roles: string[];
  status: number;
  createdAt: string;
}

export interface AdminUserListParams {
  keyword?: string;
  role?: string;
  status?: number;
  page?: number;
  size?: number;
}

export interface AdminQuestionListParams {
  id?: number;
  keyword?: string;
  difficulty?: string;
  status?: number;
  createdBy?: number;
  page?: number;
  size?: number;
}

export interface AdminDbQuestionListParams {
  id?: number;
  type?: string;
  keyword?: string;
  status?: number;
  createdBy?: number;
  page?: number;
  size?: number;
}

export interface AdminObjectiveListParams {
  id?: number;
  type?: string;
  keyword?: string;
  status?: number;
  createdBy?: number;
  page?: number;
  size?: number;
}

export const adminApi = {
  listUsers: (params: AdminUserListParams) =>
    http.get<PageResult<AdminUser>>('/admin/users', { params }),
  updateUserRoles: (id: number, roles: string[]) =>
    http.put<AdminUser>(`/admin/users/${id}/roles`, { roles }),
  updateUserStatus: (id: number, status: number) =>
    http.put<AdminUser>(`/admin/users/${id}/status`, { status }),
  resetUserPassword: (id: number, newPassword: string) =>
    http.put<void>(`/admin/users/${id}/password`, { newPassword }),
  listQuestions: (params: AdminQuestionListParams) =>
    http.get<PageResult<Question>>('/admin/questions', { params }),
  listDbQuestions: (params: AdminDbQuestionListParams) =>
    http.get<PageResult<DbQuestion>>('/admin/db-questions', { params }),
  listObjectiveQuestions: (params: AdminObjectiveListParams) =>
    http.get<PageResult<ObjectiveQuestion>>('/admin/objective-questions', { params }),
  listCodeSubmissions: (params: AdminSubmissionListParams) =>
    http.get<PageResult<CodeSubmissionRow>>('/admin/submissions', { params }),
  listDbSubmissions: (params: AdminSubmissionListParams) =>
    http.get<PageResult<DbSubmissionRow>>('/admin/db-submissions', { params }),
  listObjectiveSubmissions: (params: AdminObjectiveSubmissionListParams) =>
    http.get<PageResult<ObjectiveSubmissionRow>>('/admin/objective-submissions', { params }),
  rejudgeSubmission: (id: number) => http.post<RejudgeResult>(`/admin/submissions/${id}/rejudge`),
  rejudgeDbSubmission: (id: number) =>
    http.post<RejudgeResult>(`/admin/db-submissions/${id}/rejudge`),
  rejudgeObjectiveSubmission: (id: number) =>
    http.post<RejudgeResult>(`/admin/objective-submissions/${id}/rejudge`),
  rejudgeObjectiveQuestion: (id: number) =>
    http.post<RejudgeResult>(`/admin/objective-questions/${id}/rejudge`),
};

/** 重判结果：单条返回 submissionId+新状态；按题批量返回 questionId+重判条数。 */
export interface RejudgeResult {
  submissionId?: number;
  questionId?: number;
  rejudged?: number;
  status: string;
}

/** 编程题/数据库题提交的通用筛选参数（后端按角色强制归属约束）。 */
export interface AdminSubmissionListParams {
  userId?: number;
  questionId?: number;
  status?: string;
  page?: number;
  size?: number;
}

/** 客观题作答的筛选参数：对错筛选替代状态。 */
export interface AdminObjectiveSubmissionListParams {
  userId?: number;
  questionId?: number;
  correct?: number;
  page?: number;
  size?: number;
}

export interface CodeSubmissionRow {
  submissionId: number;
  userId: number;
  username: string;
  questionId: number;
  questionTitle: string | null;
  language: string;
  codeContent: string;
  status: string;
  score: number | null;
  timeUsedMs: number | null;
  memoryUsedKb: number | null;
  createdAt: string;
}

export interface DbSubmissionRow {
  submissionId: number;
  userId: number;
  username: string;
  questionId: number;
  questionTitle: string | null;
  status: string;
  score: number | null;
  timeUsedMs: number | null;
  sqlContent: string;
  createdAt: string;
}

export interface ObjectiveSubmissionRow {
  submissionId: number;
  userId: number;
  username: string;
  questionId: number;
  questionTitle: string;
  type: string;
  answer: string;
  /** 1 答对 / 0 答错 */
  correct: number;
  correctAnswer: string;
  score: number | null;
  fullScore: number | null;
  createdAt: string;
}
