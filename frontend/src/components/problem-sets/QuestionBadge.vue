<script setup lang="ts">
import { computed } from 'vue'
import type { QuestionType } from './types'

const props = defineProps<{ value?: string; questionType: QuestionType }>()
const labels: Record<string, string> = {
  EASY: '简单', MEDIUM: '中等', HARD: '困难', SELECT: '查询题', DML: '操作题',
  SINGLE: '单选题', MULTIPLE: '多选题', JUDGE: '判断题', FILL: '填空题',
}
const label = computed(() => labels[props.value || ''] || { ALGO: '编程题', DB: 'SQL', OBJECTIVE: '客观题' }[props.questionType])
</script>

<template><span class="question-badge" :class="value?.toLowerCase()">{{ label }}</span></template>

<style scoped>
.question-badge { display: inline-flex; align-items: center; flex-shrink: 0; padding: 3px 8px; border: 1px solid var(--line); background: var(--bg); color: var(--ink-secondary); font-size: 13px; line-height: 1.5; font-weight: 600; white-space: nowrap; }
.easy { color: #216b36; background: #eef7f0; border-color: #c6dfcd; }
.medium { color: #865211; background: #fbf5e9; border-color: #e8d8b8; }
.hard { color: #a52c35; background: #fff0f1; border-color: #eac5c9; }
.multiple { background: #e5e7eb; color: #282d35; }
.judge, .fill, .dml { background: #30343a; color: #f8f9fb; border-color: #30343a; }
</style>
