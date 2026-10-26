<script setup lang="ts">
import { computed, ref } from 'vue'
import type { HeatmapData, HeatmapDay } from '@/api/profile'

const props = defineProps<{
  data: HeatmapData | null
  loading?: boolean
}>()

interface WeekColumn {
  monthLabel?: string
  days: (HeatmapDay | null)[]
}

const tooltip = ref<{
  visible: boolean
  x: number
  y: number
  text: string
}>({
  visible: false,
  x: 0,
  y: 0,
  text: '',
})

function getDayOfWeek(dateStr: string): number {
  const [year, month, day] = dateStr.split('-').map(Number)
  const d = new Date(year, month - 1, day)
  return (d.getDay() + 6) % 7 // Monday = 0, ..., Sunday = 6
}

const weeks = computed<WeekColumn[]>(() => {
  if (!props.data || !props.data.days || props.data.days.length === 0) {
    return []
  }

  const result: WeekColumn[] = []
  let currentWeek: (HeatmapDay | null)[] = []
  let lastMonth = -1

  // 补齐第一周前面的空位
  const firstDay = props.data.days[0]
  const firstDayOfWeek = getDayOfWeek(firstDay.date)
  for (let i = 0; i < firstDayOfWeek; i++) {
    currentWeek.push(null)
  }

  for (const day of props.data.days) {
    const dayOfWeek = getDayOfWeek(day.date)
    if (dayOfWeek === 0 && currentWeek.length > 0) {
      result.push({ days: currentWeek })
      currentWeek = []
    }
    currentWeek.push(day)
  }

  // 补齐最后一周后面的空位
  if (currentWeek.length > 0) {
    while (currentWeek.length < 7) {
      currentWeek.push(null)
    }
    result.push({ days: currentWeek })
  }

  const monthNames = [
    '1月',
    '2月',
    '3月',
    '4月',
    '5月',
    '6月',
    '7月',
    '8月',
    '9月',
    '10月',
    '11月',
    '12月',
  ]
  for (const week of result) {
    const validDay = week.days.find((d) => d !== null)
    if (validDay) {
      const [, m] = validDay.date.split('-').map(Number)
      if (m !== lastMonth) {
        week.monthLabel = monthNames[m - 1]
        lastMonth = m
      }
    }
  }

  return result
})

function getLevelClass(count: number): string {
  if (count <= 0) return 'lvl-0'
  if (count <= 2) return 'lvl-1'
  if (count <= 5) return 'lvl-2'
  if (count <= 9) return 'lvl-3'
  return 'lvl-4'
}

function onCellMouseEnter(e: MouseEvent, day: HeatmapDay | null) {
  if (!day) return
  const text = `${day.date}: ${day.count} 题通过 (AC)`
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  tooltip.value = {
    visible: true,
    x: rect.left + rect.width / 2,
    y: rect.top - 8,
    text,
  }
}

function onCellMouseLeave() {
  tooltip.value.visible = false
}
</script>

<template>
  <div class="heatmap-wrapper card">
    <div class="heatmap-header">
      <div class="heatmap-title-group">
        <div class="title-row">
          <h3 class="heatmap-title">年度 AC 打卡记录</h3>
          <span class="heatmap-badge">52 周</span>
        </div>
        <span class="heatmap-sub">记录过去 365 天算法题与数据库题目的 Accepted 评测记录</span>
      </div>

      <div class="heatmap-stats">
        <div class="stat-box">
          <span class="stat-label">年内累计通过</span>
          <div class="stat-num-row">
            <span class="stat-val mono">{{ data?.totalAc ?? 0 }}</span>
            <span class="stat-unit">题</span>
          </div>
        </div>
        <div class="stat-box">
          <span class="stat-label">最长连续打卡</span>
          <div class="stat-num-row">
            <span class="stat-val mono">{{ data?.maxStreak ?? 0 }}</span>
            <span class="stat-unit">天</span>
          </div>
        </div>
        <div class="stat-box">
          <span class="stat-label">当前连续打卡</span>
          <div class="stat-num-row">
            <span class="stat-val mono">{{ data?.currentStreak ?? 0 }}</span>
            <span class="stat-unit">天</span>
          </div>
        </div>
      </div>
    </div>

    <div v-if="loading" class="heatmap-loading muted">
      <div class="loading-spinner"></div><p class="muted" style="margin: 0">正在加载热力图数据…</p>
    </div>

    <div v-else class="heatmap-scroll">
      <div class="heatmap-body">
        <!-- 星期几标签 (只展示周一、周三、周五) -->
        <div class="weekday-labels">
          <div class="weekday-spacer"></div>
          <div class="weekday-label">一</div>
          <div class="weekday-label"></div>
          <div class="weekday-label">三</div>
          <div class="weekday-label"></div>
          <div class="weekday-label">五</div>
          <div class="weekday-label"></div>
          <div class="weekday-label"></div>
        </div>

        <!-- 52 周网格 -->
        <div class="weeks-container">
          <!-- 月份标签行 -->
          <div class="months-row">
            <div
              v-for="(week, wIdx) in weeks"
              :key="'m-' + wIdx"
              class="month-cell"
            >
              <span v-if="week.monthLabel" class="month-label mono">{{ week.monthLabel }}</span>
            </div>
          </div>

          <!-- 列网格 -->
          <div class="grid-columns">
            <div
              v-for="(week, wIdx) in weeks"
              :key="'w-' + wIdx"
              class="week-col"
            >
              <div
                v-for="(day, dIdx) in week.days"
                :key="'d-' + wIdx + '-' + dIdx"
                class="day-cell"
                :class="[day ? getLevelClass(day.count) : 'lvl-empty']"
                @mouseenter="onCellMouseEnter($event, day)"
                @mouseleave="onCellMouseLeave"
              ></div>
            </div>
          </div>
        </div>
      </div>

      <!-- 图例 -->
      <div class="heatmap-footer">
        <span class="legend-text">少</span>
        <div class="day-cell lvl-0"></div>
        <div class="day-cell lvl-1"></div>
        <div class="day-cell lvl-2"></div>
        <div class="day-cell lvl-3"></div>
        <div class="day-cell lvl-4"></div>
        <span class="legend-text">多</span>
      </div>
    </div>

    <!-- 浮动 Tooltip -->
    <Teleport to="body">
      <div
        v-if="tooltip.visible"
        class="heatmap-tooltip mono"
        :style="{
          left: tooltip.x + 'px',
          top: tooltip.y + 'px',
        }"
      >
        {{ tooltip.text }}
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.heatmap-wrapper {
  padding: 24px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 0;
}

.heatmap-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 24px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--line);
}

.heatmap-title-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.heatmap-title {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: var(--ink);
  letter-spacing: -0.3px;
}

.heatmap-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 7px;
  background: var(--bg);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
}

.heatmap-sub {
  font-size: 13px;
  color: var(--ink-tertiary);
}

.heatmap-stats {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.stat-box {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 8px 16px;
  background: var(--bg);
  border: 1px solid var(--line);
  min-width: 105px;
}

.stat-label {
  font-size: 11px;
  color: var(--ink-tertiary);
  margin-bottom: 2px;
}

.stat-num-row {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.stat-val {
  font-size: 20px;
  font-weight: 800;
  color: var(--ink);
  line-height: 1;
}

.stat-unit {
  font-size: 11px;
  color: var(--ink-secondary);
}

.heatmap-loading {
  padding: 36px 0;
  text-align: center;
  font-size: 14px;
}

.heatmap-scroll {
  overflow-x: auto;
  padding-bottom: 4px;
}

.heatmap-body {
  display: flex;
  gap: 8px;
  min-width: max-content;
}

.weekday-labels {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.weekday-spacer {
  height: 18px; /* 对应月份行高度 */
}

.weekday-label {
  width: 16px;
  height: 12px;
  line-height: 12px;
  font-size: 10px;
  color: var(--ink-tertiary);
  text-align: center;
}

.weeks-container {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.months-row {
  display: flex;
  gap: 3px;
  height: 18px;
}

.month-cell {
  width: 12px;
  position: relative;
}

.month-label {
  position: absolute;
  top: 0;
  left: 0;
  font-size: 11px;
  font-weight: 500;
  color: var(--ink-secondary);
  white-space: nowrap;
  pointer-events: none;
}

.grid-columns {
  display: flex;
  gap: 3px;
}

.week-col {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.day-cell {
  width: 12px;
  height: 12px;
  border-radius: 0;
  box-sizing: border-box;
  transition: transform 0.1s ease;
}

.day-cell:hover {
  outline: 1.5px solid var(--ink);
  transform: scale(1.2);
  z-index: 2;
}

/* 严格直角，5 级单色/翠绿微渐变 */
.lvl-empty {
  visibility: hidden;
}

.lvl-0 {
  background: #f4f4f5;
  border: 1px solid #e4e4e7;
}

.lvl-1 {
  background: #bbf7d0;
  border: 1px solid #86efac;
}

.lvl-2 {
  background: #4ade80;
  border: 1px solid #22c55e;
}

.lvl-3 {
  background: #16a34a;
  border: 1px solid #15803d;
}

.lvl-4 {
  background: #14532d;
  border: 1px solid #052e16;
}

.heatmap-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  margin-top: 16px;
}

.legend-text {
  font-size: 11px;
  color: var(--ink-tertiary);
  margin: 0 4px;
}

/* 浮动提示 */
.heatmap-tooltip {
  position: fixed;
  transform: translate(-50%, -100%);
  z-index: 999;
  padding: 6px 12px;
  font-size: 12px;
  color: #fff;
  background: #111;
  border: 1px solid #333;
  border-radius: 0;
  white-space: nowrap;
  pointer-events: none;
  box-shadow: var(--shadow-sm);
}

@media (max-width: 860px) {
  .heatmap-header {
    flex-direction: column;
    align-items: flex-start;
  }
  .heatmap-stats {
    width: 100%;
    justify-content: space-between;
  }
  .stat-box {
    flex: 1;
    min-width: 90px;
  }
}
</style>
