<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{
  content: string
}>()

const html = computed(() => renderMarkdown(props.content))
</script>

<template>
  <!-- eslint-disable-next-line vue/no-v-html -- Markdown HTML is sanitized in renderMarkdown. -->
  <article class="solution-article prose" v-html="html"></article>
</template>

<style scoped>
.solution-article {
  color: var(--ink);
  font-size: 15px;
  line-height: 1.85;
  overflow-wrap: anywhere;
}
.solution-article :deep(h1),
.solution-article :deep(h2),
.solution-article :deep(h3) {
  margin: 32px 0 14px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line);
  color: var(--ink);
}
.solution-article :deep(h1) { font-size: 25px; }
.solution-article :deep(h2) { font-size: 20px; }
.solution-article :deep(h3) { font-size: 17px; }
.solution-article :deep(p), .solution-article :deep(ul), .solution-article :deep(ol) { margin: 14px 0; }
.solution-article :deep(li + li) { margin-top: 6px; }
.solution-article :deep(a) { color: var(--ink); text-decoration: underline; text-underline-offset: 3px; }
.solution-article :deep(blockquote) { margin: 18px 0; padding: 10px 16px; border-left: 3px solid var(--ink); background: var(--bg); color: var(--ink-secondary); }
.solution-article :deep(:not(pre) > code) { padding: 2px 5px; border: 1px solid var(--line); background: var(--bg); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: .9em; }
.solution-article :deep(table) { width: 100%; margin: 18px 0; border-collapse: collapse; font-size: 14px; }
.solution-article :deep(th), .solution-article :deep(td) { padding: 9px 12px; border: 1px solid var(--line); text-align: left; }
.solution-article :deep(th) { background: var(--bg); }
</style>
