<script setup lang="ts" generic="T extends object">
import { computed } from 'vue'
import type { CSSProperties, VNodeChild } from 'vue'

interface DataTableColumn {
  key: string
  label: string
  width?: string
  align?: 'left' | 'center' | 'right'
}

interface DataTableSlotProps<Row extends object> {
  row: Row
  index: number
}

const props = withDefaults(
  defineProps<{
    columns: DataTableColumn[]
    rows: readonly T[]
    rowKey?: string
    loading?: boolean
    emptyText?: string
  }>(),
  { rowKey: 'id', loading: false, emptyText: '暂无数据' },
)

/**
 * 行类型由使用方的 rows 推导，插槽作用域保持同一行类型。
 * 单元格插槽名为 cell-<column.key>，提供时覆盖默认的 row[key] 渲染。
 */
const slots = defineSlots<{
  [key: `cell-${string}`]: (props: DataTableSlotProps<T>) => VNodeChild
  actions?: (props: DataTableSlotProps<T>) => VNodeChild
}>()

const colSpan = computed(() => props.columns.length + (slots.actions ? 1 : 0))

function columnStyle(col: DataTableColumn): CSSProperties {
  return { width: col.width, textAlign: col.align }
}

function cellStyle(col: DataTableColumn): CSSProperties {
  return { textAlign: col.align }
}

function cellValue(row: T, key: string): unknown {
  return row[key as keyof T]
}

function rowKeyValue(row: T, rowKey: string, fallback: number): PropertyKey {
  const value = cellValue(row, rowKey)
  return typeof value === 'string' || typeof value === 'number' || typeof value === 'symbol'
    ? value
    : fallback
}
</script>

<template>
  <div class="data-table-wrap">
    <table class="data-table">
      <thead>
        <tr>
          <th v-for="col in columns" :key="col.key" :style="columnStyle(col)">{{ col.label }}</th>
          <th v-if="slots.actions">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="loading" class="state-row">
          <td :colspan="colSpan" class="state-cell"><div class="loading-spinner"></div><p class="muted" style="margin: 0">正在加载数据…</p></td>
        </tr>
        <tr v-else-if="rows.length === 0" class="state-row">
          <td :colspan="colSpan" class="state-cell state-empty">{{ emptyText }}</td>
        </tr>
        <template v-else>
          <tr v-for="(row, index) in rows" :key="rowKeyValue(row, rowKey, index)">
            <td v-for="col in columns" :key="col.key" :style="cellStyle(col)">
              <slot :name="`cell-${col.key}`" :row="row" :index="index">{{ cellValue(row, col.key) }}</slot>
            </td>
            <td v-if="slots.actions">
              <slot name="actions" :row="row" :index="index" />
            </td>
          </tr>
        </template>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.data-table-wrap {
  overflow-x: auto;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}
.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}
.data-table th,
.data-table td {
  padding: 10px 14px;
  text-align: left;
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
}
.data-table thead th {
  background: var(--bg);
  color: var(--ink-secondary);
  font-size: 13px;
  font-weight: 600;
}
.data-table tbody tr:last-child td {
  border-bottom: none;
}
.data-table tbody tr:not(.state-row):hover td {
  background: var(--bg);
}
.state-cell {
  padding: 28px 0;
  text-align: center;
  color: var(--ink-tertiary);
}
.state-empty {
  padding: 40px 0;
}
</style>
