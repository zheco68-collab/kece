import { describe, expect, it } from 'vitest'
import {
  DB_TYPE_TONE_MAP,
  DIFFICULTY_TONE_MAP,
  formatLanguageLabel,
  getDialectLabel,
  LANGUAGE_LABELS,
  LANGUAGE_ORDER,
  normalizeLanguageKey,
  OBJECTIVE_TYPE_LABELS,
  OBJECTIVE_TYPE_TONE_MAP,
  sortLanguages,
} from './questionMeta'

describe('questionMeta', () => {
  it('暴露标准语言顺序与标签映射（含 ArkTS）', () => {
    expect(LANGUAGE_ORDER).toEqual(['cpp', 'java', 'python', 'arkts'])
    expect(LANGUAGE_LABELS).toEqual({
      cpp: 'C++',
      java: 'Java',
      python: 'Python',
      arkts: 'ArkTS',
    })
  })

  it('normalizeLanguageKey 与 formatLanguageLabel 兼容大小写及别名', () => {
    expect(normalizeLanguageKey('CPP')).toBe('cpp')
    expect(normalizeLanguageKey(' C++ ')).toBe('cpp')
    expect(normalizeLanguageKey('ArkTS')).toBe('arkts')
    expect(normalizeLanguageKey('py')).toBe('python')
    expect(normalizeLanguageKey('Rust')).toBe('rust')

    expect(formatLanguageLabel('CPP')).toBe('C++')
    expect(formatLanguageLabel('JAVA')).toBe('Java')
    expect(formatLanguageLabel('PYTHON')).toBe('Python')
    expect(formatLanguageLabel('ARKTS')).toBe('ArkTS')
    expect(formatLanguageLabel('Go')).toBe('Go')
    expect(formatLanguageLabel('')).toBe('')
  })

  it('sortLanguages 按标准顺序排序、去重且将未知语言置后', () => {
    expect(sortLanguages(['ARKTS', 'PYTHON', 'CPP', 'JAVA', 'cpp'])).toEqual([
      'cpp',
      'java',
      'python',
      'arkts',
    ])
    expect(sortLanguages(['rust', 'python', 'go', 'cpp'])).toEqual([
      'cpp',
      'python',
      'rust',
      'go',
    ])
    expect(sortLanguages([])).toEqual([])
    expect(sortLanguages(undefined)).toEqual([])
  })

  it('getDialectLabel 将 MYSQL 或缺省值映射为 MySQL 8.0', () => {
    expect(getDialectLabel()).toBe('MySQL 8.0')
    expect(getDialectLabel(null)).toBe('MySQL 8.0')
    expect(getDialectLabel('')).toBe('MySQL 8.0')
    expect(getDialectLabel('MYSQL')).toBe('MySQL 8.0')
    expect(getDialectLabel('mysql')).toBe('MySQL 8.0')
    expect(getDialectLabel('MySQL 8.0')).toBe('MySQL 8.0')
    expect(getDialectLabel('Oracle')).toBe('Oracle')
  })

  it('提供完整的客观题标签与四态 HUD 胶囊色调映射', () => {
    expect(OBJECTIVE_TYPE_LABELS).toEqual({
      SINGLE: '单选题',
      MULTIPLE: '多选题',
      JUDGE: '判断题',
      FILL: '填空题',
    })
    expect(DIFFICULTY_TONE_MAP).toEqual({
      EASY: 'teal',
      MEDIUM: 'warning',
      HARD: 'danger',
    })
    expect(DB_TYPE_TONE_MAP).toEqual({
      SELECT: 'teal',
      DML: 'warning',
    })
    expect(OBJECTIVE_TYPE_TONE_MAP).toEqual({
      SINGLE: 'teal',
      MULTIPLE: 'warning',
      JUDGE: 'neutral',
      FILL: 'danger',
    })
  })
})
