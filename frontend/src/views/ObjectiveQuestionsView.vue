<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { RouterLink, useRouter } from 'vue-router';
import { objectiveApi } from '@/api/objective';
import Pagination from '@/components/Pagination.vue';
import { useBankUrlSync, type BankStatusFilter } from '@/shared/composables/useBankUrlSync';
import { fetchAllPagedItems, pickRandomFromPagedPool, randomInt } from '@/shared/lib/randomSample';
import { useUserStore } from '@/stores/user';
import '@/styles/question-bank.css';
import type { ObjectiveQuestion, ObjectiveType } from '@/types';
import { stripMarkdown } from '@/utils/plainText';
import { OBJECTIVE_TYPE_LABELS, OBJECTIVE_TYPE_TONE_MAP } from '@/utils/questionMeta';

const router = useRouter();
const userStore = useUserStore();

const typeLevels: Array<ObjectiveType | ''> = ['', 'SINGLE', 'MULTIPLE', 'JUDGE', 'FILL'];
const statusOptions: ReadonlyArray<{ value: BankStatusFilter; label: string }> = [
  { value: 'ALL', label: '全部' },
  { value: 'UNSOLVED', label: '未解答' },
  { value: 'SOLVED', label: '已通过' },
];

const {
  keyword,
  type,
  status: statusFilter,
  page,
  reset: resetUrlState,
} = useBankUrlSync<string, ObjectiveType>({
  fields: ['keyword', 'type', 'status', 'page'],
  validTypes: ['SINGLE', 'MULTIPLE', 'JUDGE', 'FILL'],
});

const size = 10;
const list = ref<ObjectiveQuestion[]>([]);
const total = ref(0);
const globalTotal = ref(0);
const loading = ref(true);
const randomLoading = ref(false);
const error = ref('');

const solved = ref<Set<number>>(new Set());
const filteredPool = ref<ObjectiveQuestion[]>([]);
const droppingIds = ref<Set<number>>(new Set());
const renderGeneration = ref(0);
let cachedFilterSignature = '';
let hasCachedPool = false;
let listRequestId = 0;
let randomRequestId = 0;
let globalProbeInFlight = false;
let isUnmounted = false;

const keywordModel = computed({
  get: () => keyword.value,
  set: (value: string) => {
    if (value === keyword.value) return;
    page.value = 1;
    keyword.value = value;
  },
});

const hasActiveFilters = computed(() =>
  Boolean(keyword.value.trim() || type.value || statusFilter.value !== 'ALL'),
);

const progressTotal = computed(() => globalTotal.value || total.value);
const solvedCount = computed(() => solved.value.size);
const progressPercentage = computed(() => {
  if (!progressTotal.value) return 0;
  return Math.min(100, Math.round((solvedCount.value / progressTotal.value) * 100));
});

function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}

function markDropping(items: readonly ObjectiveQuestion[]) {
  if (prefersReducedMotion()) {
    droppingIds.value = new Set();
    return;
  }
  renderGeneration.value += 1;
  droppingIds.value = new Set(items.map((item) => item.id));
}

function handleCardAnimationEnd(event: AnimationEvent, id: number) {
  if (event.target === event.currentTarget) {
    droppingIds.value.delete(id);
  }
}

function buildFilterCacheSignature(): string {
  const solvedIdsSignature = Array.from(solved.value)
    .sort((a, b) => a - b)
    .join(',');
  return JSON.stringify([keyword.value.trim(), type.value, statusFilter.value, solvedIdsSignature]);
}

function selectType(nextType: ObjectiveType | '') {
  if (type.value === nextType) return;
  page.value = 1;
  type.value = nextType;
}

function selectStatus(nextStatus: BankStatusFilter) {
  if (statusFilter.value === nextStatus) return;
  page.value = 1;
  statusFilter.value = nextStatus;
}

function resetFilters() {
  resetUrlState();
}

function changePage(nextPage: number) {
  if (nextPage === page.value) return;
  page.value = nextPage;
}

async function probeGlobalTotal() {
  if (globalTotal.value > 0 || globalProbeInFlight) return;
  globalProbeInFlight = true;
  try {
    const probe = await objectiveApi.list({ page: 1, size: 1 });
    if (!isUnmounted && probe.total > 0) {
      globalTotal.value = probe.total;
    }
  } catch {
    /* 全库总量探测失败时降级使用当前 total */
  } finally {
    globalProbeInFlight = false;
  }
}

async function load() {
  const reqId = ++listRequestId;
  error.value = '';

  const activeKeyword = keyword.value.trim() || undefined;
  const activeType = type.value || undefined;

  if (statusFilter.value !== 'ALL') {
    const signature = buildFilterCacheSignature();
    if (hasCachedPool && cachedFilterSignature === signature) {
      total.value = filteredPool.value.length;
      const start = (page.value - 1) * size;
      const paged = filteredPool.value.slice(start, start + size);
      list.value = paged;
      markDropping(paged);
      loading.value = false;
      return;
    }
  }

  loading.value = true;
  try {
    if (statusFilter.value === 'ALL') {
      filteredPool.value = [];
      cachedFilterSignature = '';
      hasCachedPool = false;

      const res = await objectiveApi.list({
        keyword: activeKeyword,
        type: activeType,
        page: page.value,
        size,
      });
      if (isUnmounted || reqId !== listRequestId) return;
      list.value = res.list;
      total.value = res.total;
      markDropping(res.list);
      if (!activeKeyword && !activeType) {
        globalTotal.value = res.total;
      } else if (globalTotal.value === 0) {
        await probeGlobalTotal();
      }
    } else {
      const signature = buildFilterCacheSignature();
      const res = await fetchAllPagedItems(
        (targetPage, pageSize) =>
          objectiveApi.list({
            keyword: activeKeyword,
            type: activeType,
            page: targetPage,
            size: pageSize,
          }),
        () => isUnmounted || reqId !== listRequestId,
      );
      if (!res || isUnmounted || reqId !== listRequestId) return;
      if (!activeKeyword && !activeType) {
        globalTotal.value = res.total;
      } else if (globalTotal.value === 0) {
        await probeGlobalTotal();
      }
      const filtered = res.list.filter((q) => {
        const isSolved = solved.value.has(q.id);
        return statusFilter.value === 'SOLVED' ? isSolved : !isSolved;
      });
      filteredPool.value = filtered;
      cachedFilterSignature = signature;
      hasCachedPool = true;
      total.value = filtered.length;
      const start = (page.value - 1) * size;
      const paged = filtered.slice(start, start + size);
      list.value = paged;
      markDropping(paged);
    }
  } catch (e) {
    if (!isUnmounted && reqId === listRequestId) {
      error.value = e instanceof Error ? e.message : '加载失败';
    }
  } finally {
    if (!isUnmounted && reqId === listRequestId) {
      loading.value = false;
    }
  }
}

async function loadSolved() {
  if (!userStore.isLoggedIn) return;
  try {
    const ids = await objectiveApi.solved();
    if (!isUnmounted) {
      solved.value = new Set(ids);
    }
  } catch {
    /* 未登录或失败时忽略 */
  }
}

// 跨全库分页均匀随机挑选一题进行挑战（已登录优先未解答）
async function pickRandomQuestion() {
  if (randomLoading.value || total.value <= 0) return;
  const reqId = ++randomRequestId;
  const stale = () => isUnmounted || reqId !== randomRequestId;
  randomLoading.value = true;
  error.value = '';

  const activeKeyword = keyword.value.trim() || undefined;
  const activeType = type.value || undefined;

  try {
    if (statusFilter.value !== 'ALL' && filteredPool.value.length > 0) {
      const target = filteredPool.value[randomInt(filteredPool.value.length)];
      if (target) router.push(`/objective/${target.id}`);
      return;
    }

    if (!userStore.isLoggedIn) {
      const picked = await pickRandomFromPagedPool({
        fetchPage: (targetPage, pageSize) =>
          objectiveApi.list({
            keyword: activeKeyword,
            type: activeType,
            page: targetPage,
            size: pageSize,
          }),
        pageSize: size,
        knownTotal: total.value,
        knownPage: page.value,
        knownList: list.value,
        isStale: stale,
      });
      if (!stale() && picked?.item) {
        router.push(`/objective/${picked.item.id}`);
      }
      return;
    }

    const all = await fetchAllPagedItems(
      (targetPage, pageSize) =>
        objectiveApi.list({
          keyword: activeKeyword,
          type: activeType,
          page: targetPage,
          size: pageSize,
        }),
      stale,
    );
    if (stale() || !all || all.list.length === 0) return;

    const unsolvedPool = all.list.filter((q) => !solved.value.has(q.id));
    const pool = unsolvedPool.length > 0 ? unsolvedPool : all.list;
    const target = pool[randomInt(pool.length)];
    if (!stale() && target) {
      router.push(`/objective/${target.id}`);
    }
  } catch (e) {
    if (!stale()) {
      error.value = e instanceof Error ? e.message : '随机抽题失败，请稍后重试';
    }
  } finally {
    if (!isUnmounted && reqId === randomRequestId) {
      randomLoading.value = false;
    }
  }
}

watch(
  [keyword, type, statusFilter, page],
  ([nextKeyword, nextType, nextStatus], [prevKeyword, prevType, prevStatus]) => {
    if (nextKeyword !== prevKeyword || nextType !== prevType || nextStatus !== prevStatus) {
      randomRequestId++;
      randomLoading.value = false;
    }
    void load();
  },
);

onMounted(async () => {
  await loadSolved();
  if (!isUnmounted) {
    await load();
  }
});

onBeforeUnmount(() => {
  isUnmounted = true;
  listRequestId++;
  randomRequestId++;
});
</script>

<template>
  <div class="questions container">
    <!-- 顶部标题与做题进度概览 -->
    <div class="page-head">
      <div class="page-head-left">
        <h1 class="page-title">客观题</h1>
        <p class="muted head-sub">单选 / 多选 / 判断 / 填空 · 即时判分与解析反馈</p>
      </div>
      <div v-if="userStore.isLoggedIn && progressTotal > 0" class="head-progress-card">
        <div class="progress-info">
          <span class="progress-title">攻克进度</span>
          <span class="progress-fraction mono">
            <strong>{{ solvedCount }}</strong> / {{ progressTotal }} 题
          </span>
        </div>
        <div class="progress-bar-track">
          <div class="progress-bar-fill" :style="{ width: `${progressPercentage}%` }"></div>
        </div>
        <span class="progress-percent mono">{{ progressPercentage }}% 完成</span>
      </div>
    </div>

    <!-- 综合筛选工具栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <div class="search-wrap">
          <label class="sr-only" for="objective-search">搜索客观题</label>
          <svg class="search-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
            <path
              d="M11.742 10.344a6.5 6.5 0 1 0-1.397 1.398h-.001c.03.04.062.078.098.115l3.85 3.85a1 1 0 0 0 1.415-1.414l-3.85-3.85a1.007 1.007 0 0 0-.115-.1zM12 6.5a5.5 5.5 0 1 1-11 0 5.5 5.5 0 0 1 11 0z"
            />
          </svg>
          <input
            id="objective-search"
            v-model="keywordModel"
            class="input search-input"
            type="search"
            placeholder="搜索题号、题目关键词…"
          />
        </div>
        <div class="type-tabs">
          <button
            v-for="t in typeLevels"
            :key="t || 'all'"
            type="button"
            class="tab-btn"
            :class="{ active: type === t }"
            :aria-pressed="type === t"
            @click="selectType(t)"
          >
            {{ t ? OBJECTIVE_TYPE_LABELS[t] : '全部题型' }}
          </button>
        </div>
      </div>

      <div class="toolbar-right">
        <!-- 做题状态快捷过滤 -->
        <div v-if="userStore.isLoggedIn" class="status-tabs">
          <button
            v-for="opt in statusOptions"
            :key="opt.value"
            type="button"
            class="status-tab"
            :class="{ active: statusFilter === opt.value }"
            :aria-pressed="statusFilter === opt.value"
            @click="selectStatus(opt.value)"
          >
            {{ opt.label }}
          </button>
        </div>

        <!-- 随机抽一题按钮 -->
        <button
          type="button"
          class="btn btn-ghost btn-sm random-btn"
          title="全库随机挑选一题进行挑战"
          :disabled="loading || randomLoading || total === 0"
          @click="pickRandomQuestion"
        >
          <span>{{ randomLoading ? '抽题中…' : '随机刷题' }}</span>
        </button>
      </div>
    </div>

    <!-- 列表加载与错误状态 -->
    <div v-if="error" class="submit-error" role="alert">
      <span>{{ error }}</span>
      <button type="button" class="btn btn-ghost btn-sm" @click="load">重试</button>
    </div>
    <div v-if="loading" class="loading-box muted">
      <span class="loading-spin" aria-hidden="true"></span>
      <span>正在加载题目…</span>
    </div>
    <div v-else-if="list.length === 0 && !error" class="empty-box">
      <p class="empty-title">未找到匹配的题目</p>
      <p class="muted empty-desc">尝试调整搜索关键词或重置筛选条件</p>
      <button
        v-if="hasActiveFilters"
        type="button"
        class="reset-filters-btn empty-reset-btn"
        @click="resetFilters"
      >
        重置筛选
      </button>
    </div>

    <!-- 单列客观题目卡片列表 -->
    <div v-else-if="list.length > 0" :key="renderGeneration" class="question-list">
      <article
        v-for="(q, index) in list"
        :key="q.id"
        class="question-item"
        :class="{ 'is-dropping': droppingIds.has(q.id) }"
        :style="{ '--drop-index': index }"
        @animationend="handleCardAnimationEnd($event, q.id)"
      >
        <!-- 左侧：通过状态图标 + 题号 + 题干摘要与分值 -->
        <div class="q-main-layout">
          <div class="q-status-wrap">
            <span
              v-if="solved.has(q.id)"
              class="status-icon solved"
              title="已攻克 (Accepted)"
              aria-label="已攻克"
            >
              <svg viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
                <path
                  d="M16 8A8 8 0 1 1 0 8a8 8 0 0 1 16 0Zm-3.97-3.03a.75.75 0 0 0-1.08.022L7.477 9.417 5.384 7.323a.75.75 0 0 0-1.06 1.06L6.97 11.03a.75.75 0 0 0 1.079-.02l3.992-4.99a.75.75 0 0 0-.01-1.05Z"
                />
              </svg>
            </span>
            <span v-else class="status-icon unsolved" title="尚未解答" aria-label="尚未解答">
              <span class="unsolved-ring"></span>
            </span>
          </div>

          <span class="q-number mono">#{{ q.id }}</span>

          <div class="q-content">
            <h2 class="q-title">
              <RouterLink :to="`/objective/${q.id}`" class="q-title-link">
                {{ stripMarkdown(q.content, 120) }}
              </RouterLink>
            </h2>
            <div class="q-tags-row">
              <span class="meta-tag limits mono">{{ q.score }} 分</span>
            </div>
          </div>
        </div>

        <!-- 右侧：题型 HUD 胶囊 + 快速答题引导 -->
        <div class="q-right-layout">
          <span class="hud-pill" :class="`tone-${OBJECTIVE_TYPE_TONE_MAP[q.type] ?? 'neutral'}`">
            {{ OBJECTIVE_TYPE_LABELS[q.type] || q.type }}
          </span>
          <span class="action-cta" aria-hidden="true">
            <span class="cta-text">开始做题</span>
            <svg
              class="cta-arrow"
              viewBox="0 0 16 16"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
            >
              <path d="M6 3l5 5-5 5" />
            </svg>
          </span>
        </div>
      </article>
    </div>

    <!-- 统一分页器 -->
    <Pagination
      v-if="total > size"
      :page="page"
      :size="size"
      :total="total"
      :disabled="loading"
      @update:page="changePage"
    />
  </div>
</template>

<style scoped>
.empty-reset-btn {
  margin-top: 14px;
}
</style>
