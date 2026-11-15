import type { DbQuestionType, Difficulty, ObjectiveType } from '@/types'

/** 算法题支持的编程语言显示顺序 */
export const LANGUAGE_ORDER: readonly string[] = ['cpp', 'java', 'python', 'arkts']

/** 语言展示名称映射 */
export const LANGUAGE_LABELS: Record<string, string> = {
  cpp: 'C++',
  java: 'Java',
  python: 'Python',
  arkts: 'ArkTS',
}

const LANGUAGE_ALIASES: Record<string, string> = {
  cpp: 'cpp',
  'c++': 'cpp',
  java: 'java',
  python: 'python',
  py: 'python',
  python3: 'python',
  arkts: 'arkts',
  ets: 'arkts',
  javascript: 'javascript',
  js: 'javascript',
}

const EXTRA_LANGUAGE_LABELS: Record<string, string> = {
  javascript: 'JS',
}

/** 规范化后端或用户输入的多语言标识（大小写/别名统一） */
export function normalizeLanguageKey(lang: string): string {
  const trimmed = (lang ?? '').trim().toLowerCase()
  if (!trimmed) return ''
  return LANGUAGE_ALIASES[trimmed] ?? trimmed
}

/** 按 LANGUAGE_ORDER 对语言列表去重并排序，未知语言排在已知语言之后 */
export function sortLanguages(languages?: readonly string[] | null): string[] {
  if (!languages?.length) return []
  const seen = new Set<string>()
  const normalized: string[] = []
  for (const item of languages) {
    const key = normalizeLanguageKey(item)
    if (key && !seen.has(key)) {
      seen.add(key)
      normalized.push(key)
    }
  }
  return normalized.sort((a, b) => {
    const idxA = LANGUAGE_ORDER.indexOf(a)
    const idxB = LANGUAGE_ORDER.indexOf(b)
    const rankA = idxA === -1 ? Number.MAX_SAFE_INTEGER : idxA
    const rankB = idxB === -1 ? Number.MAX_SAFE_INTEGER : idxB
    return rankA - rankB
  })
}

/** 格式化语言显示标签 */
export function formatLanguageLabel(lang: string): string {
  const key = normalizeLanguageKey(lang)
  if (!key) return ''
  return LANGUAGE_LABELS[key] ?? EXTRA_LANGUAGE_LABELS[key] ?? lang.trim()
}

const DIALECT_LABELS: Record<string, string> = {
  MYSQL: 'MySQL 8.0',
  MYSQL8: 'MySQL 8.0',
  MYSQL_8: 'MySQL 8.0',
  POSTGRESQL: 'PostgreSQL',
  POSTGRES: 'PostgreSQL',
  SQLITE: 'SQLite',
}

/** 规范化数据库方言展示名称 */
export function getDialectLabel(dialect?: string | null): string {
  const trimmed = (dialect ?? '').trim()
  if (!trimmed) return 'MySQL 8.0'
  return DIALECT_LABELS[trimmed.toUpperCase()] ?? trimmed
}

/** 客观题题型中文标签 */
export const OBJECTIVE_TYPE_LABELS: Record<ObjectiveType, string> = {
  SINGLE: '单选题',
  MULTIPLE: '多选题',
  JUDGE: '判断题',
  FILL: '填空题',
}

/** Precision Lab HUD 胶囊四态语义色调 */
export type PillTone = 'teal' | 'warning' | 'danger' | 'neutral'

/** 算法题难度 -> HUD 胶囊色调映射 */
export const DIFFICULTY_TONE_MAP: Record<Difficulty, PillTone> = {
  EASY: 'teal',
  MEDIUM: 'warning',
  HARD: 'danger',
}

/** 数据库题型 -> HUD 胶囊色调映射 */
export const DB_TYPE_TONE_MAP: Record<DbQuestionType, PillTone> = {
  SELECT: 'teal',
  DML: 'warning',
}

/** 客观题题型 -> HUD 胶囊色调映射 */
export const OBJECTIVE_TYPE_TONE_MAP: Record<ObjectiveType, PillTone> = {
  SINGLE: 'teal',
  MULTIPLE: 'warning',
  JUDGE: 'neutral',
  FILL: 'danger',
}
