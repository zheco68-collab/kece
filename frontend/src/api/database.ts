import { http } from './request'
import type {
  DbQuestion,
  DbQuestionType,
  DbSubmissionRecord,
  PageResult,
  Submission,
  SubmissionStatus,
} from '@/types'

export interface DbQuestionListParams {
  type?: string
  keyword?: string
  userStatus?: string
  page?: number
  size?: number
}

export interface DbSubmissionListParams {
  userId?: number
  status?: SubmissionStatus | ''
  page?: number
  size?: number
}

/** 数据库题创建/更新请求：表结构与标准答案在题目上，测试点只带初始数据。 */
export interface DbQuestionWriteRequest {
  title: string
  description: string
  dialect?: string
  type: DbQuestionType
  /** 建表语句，该题所有测试点共用。 */
  schemaSql: string
  /** 标准答案 SQL。 */
  referenceSql: string
  /** DML 判分用的校验查询；SELECT 题留空。 */
  verifySql?: string
  orderSensitive?: boolean
  /** 状态：1 公开 0 私有。 */
  status?: number
  testCases: Array<{ seedSql: string; expectedOutput?: string; isSample?: number }>
}

export const databaseApi = {
  list: (params: DbQuestionListParams) =>
    http.get<PageResult<DbQuestion>>('/db-questions', { params }),
  detail: (id: number | string, practiceCode?: string) =>
    http.get<DbQuestion>(`/db-questions/${id}`, {
      params: practiceCode ? { practiceCode } : undefined,
    }),
  submit: (
    id: number | string,
    sql: string,
    practiceCtx?: { practiceCode?: string; practiceQuestionId?: number },
  ) =>
    http.post<Submission>(`/db-questions/${id}/submit`, {
      sql,
      ...(practiceCtx?.practiceCode ? { practiceCode: practiceCtx.practiceCode } : {}),
      ...(practiceCtx?.practiceQuestionId
        ? { practiceQuestionId: practiceCtx.practiceQuestionId }
        : {}),
    }),
  listSubmissions: (params?: DbSubmissionListParams) =>
    http.get<PageResult<DbSubmissionRecord>>('/db-submissions', { params }),
  getSubmission: (id: number | string) => http.get<Submission>(`/db-submissions/${id}`),
  /** 当前用户已通过的数据库题目 id 列表。 */
  solved: () => http.get<number[]>('/db-submissions/solved'),
  create: (body: DbQuestionWriteRequest) => http.post<DbQuestion>('/db-questions', body),
  update: (id: number | string, body: DbQuestionWriteRequest) =>
    http.put<DbQuestion>(`/db-questions/${id}`, body),
  remove: (id: number | string) => http.delete<void>(`/db-questions/${id}`),
}
