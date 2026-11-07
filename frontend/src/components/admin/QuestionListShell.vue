<script setup lang="ts">
defineProps<{ title: string; description: string; total: number }>()
defineEmits<{ reset: [] }>()
</script>

<template>
  <div class="question-list">
    <header class="page-heading">
      <div>
        <h1>{{ title }}</h1>
        <p>{{ description }}</p>
      </div>
      <div class="page-action"><slot name="action" /></div>
    </header>
    <section class="filter-panel" aria-label="筛选条件">
      <div class="filter-heading"><h2>筛选条件</h2><button class="reset" type="button" @click="$emit('reset')">重置筛选</button></div>
      <div class="filter-fields"><slot name="filters" /></div>
    </section>
    <section class="results-panel" aria-label="题目列表">
      <div class="results-heading"><h2>题目列表</h2><span>共 {{ total }} 道题</span></div>
      <slot />
    </section>
  </div>
</template>

<style scoped>
.question-list { width: 100%; min-width: 0; padding: 28px 0 48px; }
.page-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 20px; margin-bottom: 24px; }
h1, h2, p { margin: 0; }
h1 { color: #111; font-size: 24px; line-height: 1.35; font-weight: 700; }
.page-heading p { margin-top: 8px; color: #555; line-height: 1.6; font-size: 14px; }
.page-action { flex-shrink: 0; }
.filter-panel, .results-panel { background: #fff; border: 1px solid #ced4da; }
.filter-panel { padding: 20px 24px 24px; margin-bottom: 20px; }
.filter-heading, .results-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
h2 { font-size: 16px; font-weight: 700; color: #111; }
.reset { border: 0; background: none; padding: 4px 0; color: #555; font: inherit; font-size: 14px; cursor: pointer; }
.reset:hover { color: #111; text-decoration: underline; }
.reset:focus-visible { outline: 2px solid #111; outline-offset: 3px; }
.filter-fields { display: flex; flex-wrap: wrap; align-items: end; gap: 16px; margin-top: 16px; }
.filter-fields :slotted(.filter-field) { display: flex; flex-direction: column; align-items: stretch; gap: 7px; min-width: 140px; font-size: 14px; color: #555; }
.results-heading { padding: 20px 24px; border-bottom: 1px solid #ced4da; }
.results-heading span { font-size: 14px; color: #555; }
.results-panel :deep(.data-table-wrap) { border: 0; }
.results-panel :deep(.data-table th), .results-panel :deep(.data-table td) { padding: 14px 20px; }
.results-panel :deep(.data-table thead th) { background: #f4f5f7; color: #555; font-size: 14px; }
.results-panel :deep(.data-table tbody tr:not(.state-row):hover td) { background: #ebeef2; }
@media (max-width: 700px) {
  .question-list { padding-top: 20px; }
  .page-heading { flex-wrap: wrap; }
  .filter-panel { padding: 18px; }
  .filter-fields :slotted(.filter-field) { flex: 1 1 180px; min-width: 0; }
  .results-heading { padding: 18px; }
}
</style>
