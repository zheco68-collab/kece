<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { SubmissionBucket, SubmissionTrend, TrendRange } from '@/api/adminOverview';

const props = defineProps<{
  range: TrendRange;
  data: SubmissionTrend | null;
  pending: boolean;
  failed: boolean;
}>();
const emit = defineEmits<{ changeRange: [range: TrendRange]; retry: [] }>();
const ranges: TrendRange[] = [7, 28];
const selected = ref('');
const rows = computed(() => props.data?.buckets ?? []);
const dateWindow = computed(() => {
  const first = rows.value[0];
  const last = rows.value.at(-1);
  return first && last ? `${shortDate(first.from)} 至 ${shortDate(last.to)}` : '';
});
const total = computed(() => rows.value.reduce((sum, row) => sum + count(row), 0));
const selectedRow = computed(() => rows.value.find((row) => row.from === selected.value));
const number = new Intl.NumberFormat('zh-CN');
const compactNumber = new Intl.NumberFormat('zh-CN', { notation: 'compact' });
const steps = computed(() => {
  const peak = Math.max(0, ...rows.value.map(count));
  const approximate = Math.max(1, peak / 4);
  const magnitude = 10 ** Math.floor(Math.log10(approximate));
  const unit = [1, 2, 5, 10].find((value) => value * magnitude >= approximate) ?? 10;
  const step = magnitude * unit;
  return [4, 3, 2, 1, 0].map((value) => value * step);
});
const scaleTop = computed(() => steps.value[0] ?? 4);

watch(
  rows,
  (current) => {
    if (!current.some((row) => row.from === selected.value)) {
      selected.value = current.at(-1)?.from ?? '';
    }
  },
  { immediate: true },
);

function count(row: SubmissionBucket) {
  return row.algoCount + row.dbCount;
}

function shortDate(date: string) {
  return date.slice(5).replace('-', '/');
}

function period(row: SubmissionBucket) {
  return row.from === row.to
    ? shortDate(row.from)
    : `${shortDate(row.from)} 至 ${shortDate(row.to)}`;
}

function columnLabel(row: SubmissionBucket) {
  return `${period(row)}，编程题 ${row.algoCount} 次，数据库题 ${row.dbCount} 次，合计 ${count(row)} 次`;
}
</script>

<template>
  <section class="trend-panel" aria-labelledby="overview-trend-heading" :aria-busy="pending">
    <header class="trend-header">
      <div>
        <h2 id="overview-trend-heading">评测提交趋势</h2>
        <p>北京时间 · {{ range === 7 ? '按完整自然日统计' : '每 7 个完整自然日汇总' }}</p>
      </div>
      <div class="range-control" role="group" aria-label="提交统计范围">
        <button
          v-for="days in ranges"
          :key="days"
          type="button"
          :aria-pressed="range === days"
          @click="emit('changeRange', days)"
        >
          {{ days === 7 ? '近 7 天' : '近 4 周' }}
        </button>
      </div>
    </header>

    <div v-if="failed" class="trend-notice" role="status">
      <span>{{ data ? '提交统计未能刷新，显示上次结果。' : '暂时无法读取提交统计。' }}</span>
      <button class="btn btn-ghost" type="button" :disabled="pending" @click="emit('retry')">
        重试
      </button>
    </div>
    <template v-if="data">
      <div class="trend-summary">
        <span class="trend-total">{{ number.format(total) }} <small>次</small></span>
        <span v-if="dateWindow">{{ dateWindow }}</span>
        <span v-if="failed"
          >上次统计
          {{
            new Date(data.asOf).toLocaleString('zh-CN', {
              timeZone: 'Asia/Shanghai',
              hour12: false,
            })
          }}</span
        >
      </div>
      <p v-if="!total" class="trend-empty">这段时间没有评测提交。</p>
      <template v-else>
        <div class="trend-legend">
          <span><i class="series-algo" aria-hidden="true" />编程题</span>
          <span><i class="series-db" aria-hidden="true" />数据库题</span>
        </div>
        <div class="trend-chart" :style="{ '--bucket-count': rows.length }">
          <div class="axis-values" aria-hidden="true">
            <span v-for="step in steps" :key="step">{{ compactNumber.format(step) }}</span>
          </div>
          <div class="plot">
            <div class="grid-lines" aria-hidden="true">
              <i v-for="step in steps" :key="step" />
            </div>
            <div class="plot-columns">
              <button
                v-for="row in rows"
                :key="row.from"
                class="plot-column"
                type="button"
                :aria-label="columnLabel(row)"
                :aria-pressed="row.from === selected"
                @click="selected = row.from"
              >
                <span
                  class="bar"
                  aria-hidden="true"
                  :style="{ height: `${(count(row) / scaleTop) * 100}%` }"
                >
                  <span class="series-db" :style="{ flexGrow: row.dbCount }" />
                  <span class="series-algo" :style="{ flexGrow: row.algoCount }" />
                </span>
              </button>
            </div>
          </div>
          <div class="axis-dates" aria-hidden="true">
            <span v-for="row in rows" :key="row.from">{{ shortDate(row.from) }}</span>
          </div>
        </div>
        <div v-if="selectedRow" class="trend-detail" aria-live="polite">
          <strong>{{ period(selectedRow) }}</strong>
          <span>编程 {{ number.format(selectedRow.algoCount) }}</span>
          <span>数据库 {{ number.format(selectedRow.dbCount) }}</span>
          <span>合计 {{ number.format(count(selectedRow)) }} 次</span>
        </div>
      </template>
    </template>
    <p v-else-if="!failed" class="trend-empty" role="status">正在加载提交统计…</p>
  </section>
</template>

<style scoped>
.trend-panel {
  --trend-algo: #282c34;
  --trend-db: #82909e;
  display: grid;
  gap: 14px;
  min-width: 0;
  padding: 20px 22px;
  background: var(--surface-white);
  border: 1px solid var(--line);
}
.trend-header,
.trend-notice,
.trend-summary,
.trend-detail,
.trend-legend {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 14px;
}
.trend-header,
.trend-notice {
  justify-content: space-between;
}
.trend-header h2 {
  margin: 0;
  font-size: 20px;
}
.trend-header p,
.trend-empty {
  margin: 0;
  color: var(--ink-secondary);
  font-size: 13px;
}
.range-control {
  display: inline-flex;
  border: 1px solid var(--line);
}
.range-control button {
  min-height: 34px;
  padding: 0 12px;
  border: 0;
  border-radius: 0;
  background: var(--surface-white);
  color: var(--ink-secondary);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.range-control button[aria-pressed='true'] {
  background: var(--button);
  color: var(--surface-white);
}
.trend-summary {
  align-items: baseline;
  color: var(--ink-secondary);
  font-size: 13px;
}
.trend-total {
  color: var(--ink);
  font-size: 26px;
  font-weight: 750;
  font-variant-numeric: tabular-nums;
}
.trend-total small {
  font-size: 13px;
  font-weight: 500;
}
.trend-legend {
  color: var(--ink-secondary);
  font-size: 13px;
}
.trend-legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.trend-legend i {
  width: 10px;
  height: 10px;
}
.series-algo {
  background: var(--trend-algo);
}
.series-db {
  background: var(--trend-db);
}
.trend-chart {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  grid-template-rows: 168px auto;
  column-gap: 9px;
  color: var(--ink-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.axis-values,
.grid-lines {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}
.axis-values {
  text-align: right;
  line-height: 12px;
  margin-block: -5px;
}
.plot {
  position: relative;
}
.grid-lines {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
.grid-lines i {
  border-top: 1px solid var(--line);
}
.plot-columns,
.axis-dates {
  display: grid;
  grid-template-columns: repeat(var(--bucket-count), minmax(0, 1fr));
}
.plot-columns {
  position: relative;
  height: 100%;
}
.plot-column {
  display: flex;
  align-items: flex-end;
  justify-content: center;
  min-width: 0;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  cursor: pointer;
}
.plot-column:hover,
.plot-column[aria-pressed='true'] {
  background: #e9edf280;
}
.bar {
  display: flex;
  flex-direction: column;
  width: min(65%, 52px);
  transition: height 0.16s ease;
}
.bar > span {
  flex-basis: 0;
  min-height: 0;
}
.axis-dates {
  grid-column: 2;
  margin-top: 5px;
  text-align: center;
  white-space: nowrap;
}
.trend-detail {
  padding: 8px 10px;
  background: var(--surface);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.trend-notice {
  color: #8b3c27;
  font-size: 13px;
}
.trend-empty {
  padding: 18px 0 12px;
}
.trend-panel :is(button):focus-visible {
  outline: 2px solid var(--ink);
  outline-offset: 2px;
  z-index: 1;
}
@media (max-width: 600px) {
  .trend-panel {
    padding: 16px 12px;
  }
  .range-control button,
  .trend-notice button {
    min-height: 44px;
  }
  .trend-chart {
    column-gap: 5px;
    font-size: 11px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .bar {
    transition: none;
  }
}
</style>
