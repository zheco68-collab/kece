<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  getProblemSetDetail,
  type ProblemSetDetail,
  type ProblemSetItem
} from '@/api/problemSet'

const STORAGE_KEY = 'kece_active_problem_set_id'

const route = useRoute()
const router = useRouter()

const activeSetId = ref<number | null>(null)
const detail = ref<ProblemSetDetail | null>(null)
const loading = ref(false)

// 解析当前路由对应的题目类型与题号
const currentQuestionCtx = computed<{ type: 'ALGO' | 'DB' | 'OBJECTIVE'; id: number } | null>(() => {
  const path = route.path
  const algoMatch = path.match(/^\/questions\/(\d+)(\/|$)/)
  if (algoMatch) {
    return { type: 'ALGO', id: Number(algoMatch[1]) }
  }
  const dbMatch = path.match(/^\/database\/(\d+)(\/|$)/)
  if (dbMatch) {
    return { type: 'DB', id: Number(dbMatch[1]) }
  }
  const objMatch = path.match(/^\/objective\/(\d+)(\/|$)/)
  if (objMatch) {
    return { type: 'OBJECTIVE', id: Number(objMatch[1]) }
  }
  return null
})

// 展平题集中的所有有效题目
const flatItems = computed<ProblemSetItem[]>(() => {
  if (!detail.value || !detail.value.sections) return []
  const list: ProblemSetItem[] = []
  for (const sec of detail.value.sections) {
    if (sec.items) {
      for (const item of sec.items) {
        list.push(item)
      }
    }
  }
  return list
})

const currentIndex = computed<number>(() => {
  const ctx = currentQuestionCtx.value
  if (!ctx || flatItems.value.length === 0) return -1
  return flatItems.value.findIndex(
    (it) => it.questionType === ctx.type && it.questionId === ctx.id
  )
})

const currentItem = computed<ProblemSetItem | null>(() => {
  if (currentIndex.value < 0) return null
  return flatItems.value[currentIndex.value] || null
})

const prevItem = computed<ProblemSetItem | null>(() => {
  for (let i = currentIndex.value - 1; i >= 0; i--) {
    const candidate = flatItems.value[i]
    if (candidate && candidate.sourceAlive !== false) return candidate
  }
  return null
})

const nextItem = computed<ProblemSetItem | null>(() => {
  if (currentIndex.value < 0) return null
  for (let i = currentIndex.value + 1; i < flatItems.value.length; i++) {
    const candidate = flatItems.value[i]
    if (candidate && candidate.sourceAlive !== false) return candidate
  }
  return null
})

// 是否显示题集上下文横幅
const visible = computed<boolean>(() => {
  return Boolean(currentQuestionCtx.value && detail.value && currentIndex.value >= 0)
})

async function loadSet(setId: number) {
  if (!setId || Number.isNaN(setId)) return
  if (detail.value && detail.value.id === setId) return
  loading.value = true
  try {
    detail.value = await getProblemSetDetail(setId)
  } catch {
    detail.value = null
    activeSetId.value = null
    sessionStorage.removeItem(STORAGE_KEY)
  } finally {
    loading.value = false
  }
}

watch(
  () => [route.path, route.query.setId] as const,
  async ([_path, querySetId]) => {
    const qCtx = currentQuestionCtx.value
    if (!qCtx) {
      // 不在单题作答/提交/结果页时，若进入了题集详情页则同步缓存
      const setMatch = route.path.match(/^\/problem-sets\/(\d+)/)
      if (setMatch) {
        const sid = Number(setMatch[1])
        activeSetId.value = sid
        sessionStorage.setItem(STORAGE_KEY, String(sid))
      }
      return
    }

    const fromQuery = querySetId ? Number(querySetId) : null
    if (fromQuery && !Number.isNaN(fromQuery)) {
      activeSetId.value = fromQuery
      sessionStorage.setItem(STORAGE_KEY, String(fromQuery))
      await loadSet(fromQuery)
      // 若剛从结果页返回，刷新一次进度
      if (route.path.includes('/result/')) {
        detail.value = await getProblemSetDetail(fromQuery).catch(() => detail.value)
      }
      return
    }

    const cached = sessionStorage.getItem(STORAGE_KEY)
    if (cached) {
      const sid = Number(cached)
      if (!Number.isNaN(sid)) {
        activeSetId.value = sid
        await loadSet(sid)
        if (route.path.includes('/result/')) {
          detail.value = await getProblemSetDetail(sid).catch(() => detail.value)
        }
      }
    }
  },
  { immediate: true }
)

function goToItem(item: ProblemSetItem | null) {
  if (!item || !activeSetId.value) return
  const query = { setId: String(activeSetId.value) }
  if (item.questionType === 'ALGO') {
    router.push({ path: `/questions/${item.questionId}`, query })
  } else if (item.questionType === 'DB') {
    router.push({ path: `/database/${item.questionId}`, query })
  } else if (item.questionType === 'OBJECTIVE') {
    router.push({ path: `/objective/${item.questionId}`, query })
  }
}

function backToProblemSet() {
  if (!activeSetId.value) return
  router.push(`/problem-sets/${activeSetId.value}`)
}

function exitProblemSetMode() {
  activeSetId.value = null
  detail.value = null
  sessionStorage.removeItem(STORAGE_KEY)
  if (route.query.setId) {
    const nextQuery = { ...route.query }
    delete nextQuery.setId
    router.replace({ path: route.path, query: nextQuery })
  }
}
</script>

<template>
  <div v-if="visible && detail" class="pset-context-bar">
    <div class="container pset-context-inner">
      <div class="pset-left">
        <button class="pset-back-btn" @click="backToProblemSet" title="返回题集目录">
          <svg class="icon-sm" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M10 3L5 8l5 5" />
          </svg>
          <span class="pset-tag">题集连刷</span>
          <span class="pset-title">{{ detail.title }}</span>
        </button>
        <span class="pset-divider">/</span>
        <span class="pset-pos mono">
          第 {{ currentIndex + 1 }} / {{ flatItems.length }} 题
        </span>
        <span
          v-if="currentItem?.solved"
          class="pset-solved-pill"
        >
          ✓ 已攻克
        </span>
      </div>

      <div class="pset-right">
        <div class="pset-progress-mini" title="当前题集攻克进度">
          <span class="mono">进度 {{ detail.solvedCount ?? 0 }}/{{ detail.itemCount }}</span>
          <div class="mini-bar-track">
            <div
              class="mini-bar-fill"
              :style="{
                width:
                  detail.itemCount > 0
                    ? `${Math.min(100, Math.round(((detail.solvedCount ?? 0) * 100) / detail.itemCount))}%`
                    : '0%'
              }"
            ></div>
          </div>
        </div>

        <div class="pset-nav-group">
          <button
            class="pset-nav-btn"
            :disabled="!prevItem"
            @click="goToItem(prevItem)"
            :title="prevItem ? `上一题：${prevItem.title}` : '已是第一题'"
          >
            ← 上一题
          </button>
          <button
            class="pset-nav-btn"
            :disabled="!nextItem"
            @click="goToItem(nextItem)"
            :title="nextItem ? `下一题：${nextItem.title}` : '已是最后一题'"
          >
            下一题 →
          </button>
        </div>

        <button class="pset-close-btn" @click="exitProblemSetMode" title="退出题集连刷模式">
          ×
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pset-context-bar {
  background: #111111;
  color: #ffffff;
  border-bottom: 1px solid #262626;
  font-size: 13px;
  z-index: 90;
}

.pset-context-inner {
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.pset-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.pset-back-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: transparent;
  border: none;
  color: #ffffff;
  cursor: pointer;
  padding: 4px 8px 4px 4px;
  font-size: 13px;
  transition: opacity 0.15s ease;
}

.pset-back-btn:hover {
  opacity: 0.85;
}

.icon-sm {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}

.pset-tag {
  background: #ffffff;
  color: #111111;
  font-size: 11px;
  font-weight: 700;
  padding: 1px 6px;
  letter-spacing: 0.5px;
}

.pset-title {
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 280px;
}

.pset-divider {
  color: #525252;
}

.pset-pos {
  color: #a3a3a3;
  font-size: 12px;
}

.pset-solved-pill {
  background: #16a34a;
  color: #ffffff;
  font-size: 11px;
  font-weight: 700;
  padding: 1px 6px;
}

.pset-right {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}

.pset-progress-mini {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #d4d4d4;
}

.mini-bar-track {
  width: 72px;
  height: 6px;
  background: #333333;
  overflow: hidden;
}

.mini-bar-fill {
  height: 100%;
  background: #22c55e;
  transition: width 0.25s ease;
}

.pset-nav-group {
  display: inline-flex;
  border: 1px solid #404040;
}

.pset-nav-btn {
  background: #1a1a1a;
  color: #f5f5f5;
  border: none;
  border-right: 1px solid #404040;
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease;
}

.pset-nav-btn:last-child {
  border-right: none;
}

.pset-nav-btn:hover:not(:disabled) {
  background: #2e2e2e;
}

.pset-nav-btn:disabled {
  color: #525252;
  cursor: not-allowed;
}

.pset-close-btn {
  background: transparent;
  border: 1px solid #404040;
  color: #a3a3a3;
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
  transition: all 0.15s ease;
}

.pset-close-btn:hover {
  color: #ffffff;
  border-color: #737373;
}

@media (max-width: 768px) {
  .pset-progress-mini {
    display: none;
  }
  .pset-title {
    max-width: 140px;
  }
}
</style>
