import { http } from './request';
import type { PageResult } from '@/types';

/** 试卷题目快照条目（与后端 ExamQuestionVO 对齐）。 */
export interface ExamQuestionItem {
  id: number;
  questionId: number;
  questionType: 'ALGO' | 'DB' | 'OBJECTIVE';
  title: string;
  score: number;
  sortOrder: number;
}

export interface Exam {
  id: number;
  title: string;
  description: string | null;
  durationMinutes: number;
  startTime: string | null;
  endTime: string | null;
  mode: 'ACM' | 'OI';
  status: number;
  requireStudentInfo?: number | boolean;
  schoolName?: string | null;
  semester?: string | null;
  sessionStatus: string | null;
  submitted?: boolean;
  createdBy: number;
  questions: ExamQuestionItem[];
  createdAt: string;
  updatedAt: string;
}

/** 试卷创建/更新请求（组卷条目：题目引用 + 分值快照）。 */
export interface ExamWriteRequest {
  title: string;
  description?: string | null;
  durationMinutes: number;
  mode: 'ACM' | 'OI';
  status?: number;
  requireStudentInfo?: number;
  schoolName?: string | null;
  semester?: string | null;
  startTime?: string | null;
  questions: Array<{
    questionId: number;
    questionType: 'ALGO' | 'DB' | 'OBJECTIVE';
    score: number;
  }>;
}

export interface UserSavedAnswer {
  examQuestionId: number;
  answerContent?: string | null;
  language?: string | null;
  status?: string | null;
  score?: number | null;
  submissionId?: number | null;
}

export interface ExamStudentInfo {
  id?: number;
  examId?: number;
  userId?: number;
  realName: string;
  studentNo: string;
  college?: string;
  major?: string;
  className?: string;
}

export interface SessionEnter {
  id: number;
  examId: number;
  examTitle: string;
  mode: 'ACM' | 'OI';
  durationMinutes: number;
  startTime: string;
  endTime: string;
  status: string;
  remainingSeconds: number;
  requireStudentInfo?: boolean;
  hasFilledStudentInfo?: boolean;
  studentInfo?: ExamStudentInfo | null;
  questions: ExamSessionQuestion[];
  myAnswers?: Record<number, UserSavedAnswer>;
}


/** 考试中单题：快照 + 实时题面（作答所需）。 */
export interface ExamSessionQuestion {
  id: number;
  questionId: number;
  questionType: 'ALGO' | 'DB' | 'OBJECTIVE';
  title: string;
  score: number;
  sortOrder: number;
  content: string | null;
  options: string[] | null;
  objectiveType: 'SINGLE' | 'MULTIPLE' | 'JUDGE' | 'FILL' | null;
  schemaSql: string | null;
  timeLimitMs: number | null;
  memoryLimitMb: number | null;
}

/** 场次（公开列表与管理列表共用；状态含 UPCOMING/ONGOING/ENDED/CANCELLED）。 */
export interface ExamSessionInfo {
  id: number;
  examId: number;
  examTitle: string;
  startTime: string;
  endTime: string;
  durationMinutes: number;
  status: string;
  createdBy: number;
  createdAt: string;
}

/** ACM 单题实时提交的返回（submissionId 供轮询判题结果）。 */
export interface SingleSubmit {
  submissionId: number;
  questionType: 'ALGO' | 'DB';
  status: string;
}

export interface SubmitResult {
  examId: number;
  graded: number;
  queued: number;
}

export interface ExamSubmissionRow {
  examQuestionId: number;
  questionId: number;
  questionType: 'ALGO' | 'DB' | 'OBJECTIVE';
  title: string;
  score: number | null;
  fullScore: number;
  answer: string;
  language: string | null;
  status: string | null;
}

export interface RankRow {
  userId: number;
  username: string;
  solved: number;
  penaltyMinutes: number;
  totalScore: number;
  finished: number;
  fullScore: number;
}

export interface ExamStudentRow {
  userId: number;
  username: string;
  realName: string;
  studentNo: string;
  college: string;
  major: string;
  className: string;
  totalScore: number;
  fullScore: number;
  solvedCount: number;
  totalQuestions: number;
  submitted: boolean;
  submittedAt?: string | null;
  penaltyMinutes?: number | null;
  questionScores?: Record<number, number>;
  questionStatuses?: Record<number, string>;
}

export interface ExamAdminOverview {
  exam: Exam;
  totalStudents: number;
  submittedCount: number;
  avgScore: number;
  maxScore: number;
  minScore: number;
  students: ExamStudentRow[];
}

export interface ExamStudentPaperQuestion {
  examQuestionId: number;
  questionId: number;
  questionType: 'ALGO' | 'DB' | 'OBJECTIVE';
  objectiveType?: 'SINGLE' | 'MULTIPLE' | 'JUDGE' | 'FILL' | null;
  title: string;
  content: string;
  options?: string[];
  fullScore: number;
  studentScore?: number | null;
  status: string;
  studentAnswer?: string | null;
  standardAnswer?: string | null;
  isCorrect?: boolean | null;
  language?: string | null;
  resultJson?: string | null;
}

export interface ExamStudentPaper {
  exam: Exam;
  studentInfo?: ExamStudentInfo | null;
  userId?: number | null;
  username?: string | null;
  totalScore?: number | null;
  fullScore: number;
  solvedCount?: number | null;
  submitted?: boolean;
  submittedAt?: string | null;
  questions: ExamStudentPaperQuestion[];
}

export const examApi = {
  listExams: (params: { keyword?: string; page?: number; size?: number }) =>
    http.get<PageResult<Exam>>('/exams', { params }),
  listAdmin: (params: {
    keyword?: string;
    status?: number;
    createdBy?: number;
    page?: number;
    size?: number;
  }) => http.get<PageResult<Exam>>('/exams/admin', { params }),
  detail: (id: number) => http.get<Exam>(`/exams/${id}`),
  create: (body: ExamWriteRequest) => http.post<Exam>('/exams', body),
  update: (id: number, body: ExamWriteRequest) => http.put<Exam>(`/exams/${id}`, body),
  remove: (id: number) => http.delete<void>(`/exams/${id}`),
  listOpen: (params: { keyword?: string; page?: number; size?: number }) =>
    http.get<PageResult<ExamSessionInfo>>('/exams/open', { params }),
  enter: (examId: number) => http.get<SessionEnter>(`/exams/${examId}/enter`),
  saveAnswer: (
    examId: number,
    body: { examQuestionId: number; answer: string; language?: string },
  ) => http.put<void>(`/exams/${examId}/answers`, body),
  submitQuestion: (examId: number, examQuestionId: number) =>
    http.post<SingleSubmit>(`/exams/${examId}/questions/${examQuestionId}/submit`),
  submit: (examId: number) => http.post<SubmitResult>(`/exams/${examId}/submit`),
  results: (examId: number) => http.get<ExamSubmissionRow[]>(`/exams/${examId}/results`),
  ranking: (examId: number) => http.get<RankRow[]>(`/exams/${examId}/ranking`),
  reportSwitch: (examId: number, delta = 1) =>
    http.post<{ count: number }>(`/exams/${examId}/switch`, { delta }),
  mySwitchCount: (examId: number) =>
    http.get<{ count: number }>(`/exams/${examId}/switch`),
  cancel: (examId: number) => http.delete<void>(`/exams/${examId}/cancel`),
  // 考生实名登记
  saveStudentInfo: (examId: number, body: ExamStudentInfo) =>
    http.post<ExamStudentInfo>(`/exams/${examId}/student-info`, body),
  getStudentInfo: (examId: number) =>
    http.get<ExamStudentInfo | null>(`/exams/${examId}/student-info`),
  // 教师查卷与全场成绩总览
  getAdminOverview: (examId: number) =>
    http.get<ExamAdminOverview>(`/exams/${examId}/admin-overview`),
  getStudentPaper: (examId: number, userId: number) =>
    http.get<ExamStudentPaper>(`/exams/${examId}/students/${userId}/paper`),
  getPaperTemplate: (examId: number) =>
    http.get<ExamStudentPaper>(`/exams/${examId}/paper-template`),
  getExportUrl: (examId: number, format: 'word' | 'pdf', studentId?: number) => {
    let url = `/api/v1/exams/${examId}/export?format=${format}`;
    if (studentId != null) url += `&studentId=${studentId}`;
    const token = localStorage.getItem('token');
    if (token) {
      url += `&token=${encodeURIComponent(token)}`;
    }
    return url;
  },
  downloadWord: (examId: number, studentId?: number) => {
    const url = examApi.getExportUrl(examId, 'word', studentId);
    const link = document.createElement('a');
    link.href = url;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  },
  openPrintPdf: (examId: number, studentId?: number) => {
    const url = examApi.getExportUrl(examId, 'pdf', studentId);
    window.open(url, '_blank');
  },
  getBatchExportUrl: (examId: number, studentIds?: number[]) => {
    let url = `/api/v1/exams/${examId}/export-batch`;
    const params: string[] = [];
    if (studentIds && studentIds.length > 0) {
      params.push(`studentIds=${studentIds.join(',')}`);
    }
    const token = localStorage.getItem('token');
    if (token) {
      params.push(`token=${encodeURIComponent(token)}`);
    }
    if (params.length > 0) {
      url += `?${params.join('&')}`;
    }
    return url;
  },
  downloadBatchWord: async (examId: number, studentIds?: number[]) => {
    const url = examApi.getBatchExportUrl(examId, studentIds);
    const token = localStorage.getItem('token');
    const headers: Record<string, string> = {};
    if (token) headers['Authorization'] = `Bearer ${token}`;

    const resp = await fetch(url, { headers });
    const contentType = resp.headers.get('content-type') || '';
    if (contentType.includes('application/json')) {
      const data = await resp.json();
      throw new Error(data.message || '批量导出试卷失败');
    }
    if (!resp.ok) {
      throw new Error(`批量导出失败 (HTTP ${resp.status})`);
    }

    let filename = '学生试卷批量导出.zip';
    const disp = resp.headers.get('content-disposition');
    if (disp) {
      const match = disp.match(/filename\*=UTF-8''([^;]+)/i) || disp.match(/filename="?([^";]+)"?/i);
      if (match && match[1]) {
        filename = decodeURIComponent(match[1]);
      }
    }

    const blob = await resp.blob();
    const blobUrl = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = blobUrl;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(blobUrl);
  },
};
