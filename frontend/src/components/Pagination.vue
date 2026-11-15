<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = defineProps<{
  page: number
  size: number
  total: number
  compact?: boolean
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:page', p: number): void
}>()

const pageCount = computed(() => Math.max(1, Math.ceil(props.total / Math.max(1, props.size))))
const jumpPage = ref('')
const validJumpPage = computed(() => {
  if (!/^[1-9]\d*$/.test(jumpPage.value)) return null
  const target = Number(jumpPage.value)
  return Number.isSafeInteger(target) && target <= pageCount.value ? target : null
})

watch(() => props.page, () => { jumpPage.value = '' })
watch(pageCount, () => { jumpPage.value = '' })

/** 页码序列，null 表示省略号：超过 7 页时只保留首页、末页、当前页及其左右一页。 */
const pages = computed<Array<number | null>>(() => {
  const last = pageCount.value
  if (last <= 7) return Array.from({ length: last }, (_, i) => i + 1)
  const kept = [...new Set([1, last, props.page - 1, props.page, props.page + 1])]
    .filter((n) => n >= 1 && n <= last)
    .sort((a, b) => a - b)
  const items: Array<number | null> = []
  let prev = 0
  for (const n of kept) {
    if (n - prev > 1) items.push(null)
    items.push(n)
    prev = n
  }
  return items
})

function go(p: number | null) {
  if (props.disabled || p === null || p < 1 || p > pageCount.value || p === props.page) return
  emit('update:page', p)
}

function jump() {
  if (validJumpPage.value === null) return
  go(validJumpPage.value)
  jumpPage.value = ''
}
</script>

<template>
  <nav v-if="total > 0" class="pagination" :class="{ compact }" aria-label="分页导航">
    <span v-if="!compact" class="page-total">共 {{ total }} 条</span>
    <div class="page-nav">
      <button type="button" class="btn btn-ghost btn-sm" :disabled="disabled || page <= 1" @click="go(page - 1)">
        上一页
      </button>
      <span v-if="compact" class="page-total">{{ page }} / {{ pageCount }}</span>
      <template v-for="(item, i) in compact ? [] : pages" :key="i">
        <span v-if="item === null" class="page-gap" aria-hidden="true">…</span>
        <button
          v-else
          type="button"
          :disabled="disabled"
          class="btn btn-ghost btn-sm page-num"
          :class="{ active: item === page }"
          :aria-current="item === page ? 'page' : undefined"
          :aria-label="`第 ${item} 页`"
          @click="go(item)"
        >
          {{ item }}
        </button>
      </template>
      <button type="button" class="btn btn-ghost btn-sm" :disabled="disabled || page >= pageCount" @click="go(page + 1)">
        下一页
      </button>
    </div>
    <form v-if="pageCount > 1" class="page-jump" @submit.prevent="jump">
      <span class="page-total">共 {{ pageCount }} 页</span>
      <input
        v-model="jumpPage"
        :disabled="disabled"
        class="input jump-input"
        type="text"
        inputmode="numeric"
        aria-label="跳转页码"
        placeholder="页码"
      />
      <button class="btn btn-ghost btn-sm" type="submit" :disabled="disabled || validJumpPage === null">跳转</button>
    </form>
  </nav>
</template>

<style scoped>
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  flex-wrap: wrap;
  padding: 20px 0;
  font-variant-numeric: tabular-nums;
}
.pagination.compact {
  padding: 0;
  gap: 12px;
  justify-content: space-between;
}
.compact .page-nav,
.compact .page-jump {
  gap: 8px;
}
.compact .btn,
.compact .jump-input {
  min-height: 40px;
  height: 40px;
  font-size: 14px;
}
.page-total {
  color: var(--ink-secondary);
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}
.page-nav {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.page-jump {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pagination .btn {
  border-radius: 0px;
  border: 1px solid var(--kece-border, #e4e7ec);
  background: var(--surface-white, #ffffff);
  color: var(--ink-secondary);
  font-variant-numeric: tabular-nums;
  transition:
    background-color 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease;
}
.pagination .btn:hover:not(:disabled) {
  opacity: 1;
  border-color: var(--kece-accent, #137969);
  color: var(--kece-accent-ink, #0f5f53);
  background: var(--kece-accent-soft, #eef6f4);
}
.pagination .btn:disabled {
  opacity: 1;
  background: var(--kece-surface-muted, #f8fafc);
  color: #5c6370;
  border-color: var(--kece-border, #e4e7ec);
  cursor: not-allowed;
}
.jump-input {
  width: 64px;
  height: 36px;
  text-align: center;
  border-radius: 0px;
  border: 1px solid var(--kece-border, #e4e7ec);
  background: var(--surface-white, #ffffff);
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}
.jump-input:focus {
  border-color: var(--kece-accent, #137969);
}
.jump-input:disabled {
  opacity: 1;
  background: var(--kece-surface-muted, #f8fafc);
  color: #5c6370;
  border-color: var(--kece-border, #e4e7ec);
  cursor: not-allowed;
}
.page-num {
  min-width: 38px;
  padding: 8px 10px;
  font-variant-numeric: tabular-nums;
}
.pagination .page-num.active {
  background: var(--kece-accent, #137969);
  border-color: var(--kece-accent, #137969);
  color: #ffffff;
  font-weight: 600;
}
.pagination .page-num.active:hover:not(:disabled) {
  opacity: 1;
  background: var(--kece-accent-hover, #0e6053);
  border-color: var(--kece-accent-hover, #0e6053);
  color: #ffffff;
}
.pagination .page-num.active:disabled {
  opacity: 1;
  background: #475569;
  border-color: #475569;
  color: #f8fafc;
}
.page-gap {
  padding: 0 2px;
  color: var(--ink-tertiary);
  font-size: 14px;
  user-select: none;
}
</style>
