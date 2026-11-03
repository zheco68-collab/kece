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
  <div v-if="total > 0" class="pagination" :class="{ compact }">
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
  </div>
</template>

<style scoped>
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  flex-wrap: wrap;
  padding: 20px 0;
}
.pagination.compact { padding: 0; gap: 12px; justify-content: space-between; }
.compact .page-nav, .compact .page-jump { gap: 8px; }
.compact .btn, .compact .jump-input { min-height: 40px; font-size: 14px; }
.page-total {
  color: var(--ink-secondary);
  font-size: 14px;
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
.jump-input {
  width: 64px;
  text-align: center;
}
.page-num {
  min-width: 38px;
  padding: 8px 10px;
}
.page-num.active {
  background: var(--button);
  border-color: var(--button);
  color: #fff;
}
.page-gap {
  padding: 0 2px;
  color: var(--ink-tertiary);
  font-size: 14px;
  user-select: none;
}
</style>
