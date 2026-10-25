import type { Difficulty, SubmissionStatus } from '@/types'

/** 各难度中文标签 */
export const DIFFICULTY_LABELS: Record<Difficulty, string> = {
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
}

/** 算法题支持评测的语言列表 */
export const SUPPORTED_LANGUAGES = ['cpp', 'java', 'python', 'arkts']

/** 各语言默认文件名映射 */
export const CODE_FILENAMES: Record<string, string> = {
  cpp: 'main.cpp',
  java: 'Main.java',
  python: 'main.py',
  arkts: 'main.ets',
}
export const FILENAMES = CODE_FILENAMES

/** 各语言做答默认样板代码 */
export const CODE_TEMPLATES: Record<string, string> = {
  cpp: `#include <iostream>
using namespace std;

int main() {
    // 在此编写代码
    return 0;
}
`,
  java: `import java.util.*;

public class Main {
    public static void main(String[] args) {
        // 在此编写代码
    }
}
`,
  python: `# 在此编写代码
`,
  arkts: `import * as fs from 'fs';

function main(): void {
    const input: string = fs.readFileSync(0, 'utf-8').trim();
    if (!input) return;
    // 在此编写代码
}

main();
`,
}
export const TEMPLATES = CODE_TEMPLATES


/** 各评测状态的缩写（节点/徽章展示用）。 */
export const STATUS_ABBR: Record<SubmissionStatus, string> = {
  PENDING: 'PD',
  COMPILING: 'CP',
  JUDGING: 'JD',
  AI_REVIEWING: 'AI',
  ACCEPTED: 'AC',
  WRONG_ANSWER: 'WA',
  TIME_LIMIT_EXCEEDED: 'TLE',
  MEMORY_LIMIT_EXCEEDED: 'MLE',
  RUNTIME_ERROR: 'RE',
  COMPILE_ERROR: 'CE',
  SYSTEM_ERROR: 'SE',
}

/** 各评测状态的中文标签展示。 */
export const STATUS_LABELS: Record<SubmissionStatus, string> = {
  PENDING: '排队中',
  COMPILING: '编译中',
  JUDGING: '评测中',
  AI_REVIEWING: 'AI评审中',
  ACCEPTED: '答案正确',
  WRONG_ANSWER: '答案错误',
  TIME_LIMIT_EXCEEDED: '运行超时',
  MEMORY_LIMIT_EXCEEDED: '内存超限',
  RUNTIME_ERROR: '运行错误',
  COMPILE_ERROR: '编译错误',
  SYSTEM_ERROR: '系统错误',
}

export const TERMINAL_STATUSES: SubmissionStatus[] = [
  'ACCEPTED',
  'WRONG_ANSWER',
  'TIME_LIMIT_EXCEEDED',
  'MEMORY_LIMIT_EXCEEDED',
  'RUNTIME_ERROR',
  'COMPILE_ERROR',
  'SYSTEM_ERROR',
]

export function isTerminal(status: SubmissionStatus): boolean {
  return TERMINAL_STATUSES.includes(status)
}

/** 语言显示名。 */
export const LANGUAGE_LABELS: Record<string, string> = {
  cpp: 'C++',
  java: 'Java',
  python: 'Python',
  arkts: 'ArkTS',
}

/**
 * 内存读数格式化：后端按 KB 上报。
 * 不足 1MB 保留 KB，其余折算为 MB（保留一位小数，整数值去掉小数位），避免节点方块上出现五位数。
 */
export function formatMemory(kb: number | null | undefined): string {
  if (kb == null) return '-'
  if (kb < 1024) return `${kb}KB`
  const mb = kb / 1024
  if (mb >= 1000) return `${Math.round(mb)}MB`
  return `${mb.toFixed(1).replace(/\.0$/, '')}MB`
}

/** 状态对应的配色基调（用于节点方块与状态徽章）。 */
export function statusTone(status: SubmissionStatus): string {
  switch (status) {
    case 'ACCEPTED':
      return 'ac'
    case 'WRONG_ANSWER':
      return 'wa'
    case 'TIME_LIMIT_EXCEEDED':
      return 'tle'
    case 'MEMORY_LIMIT_EXCEEDED':
      return 'mle'
    case 'RUNTIME_ERROR':
      return 're'
    case 'COMPILE_ERROR':
      return 'ce'
    case 'SYSTEM_ERROR':
      return 'se'
    default:
      return 'run'
  }
}
