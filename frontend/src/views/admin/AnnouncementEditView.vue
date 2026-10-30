<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import {
  createAdminAnnouncement,
  getAdminAnnouncementDetail,
  updateAdminAnnouncement,
  type AnnouncementSavePayload,
} from '@/api/announcement'
import { ApiError } from '@/api/request'
import MarkdownEditor from '@/components/MarkdownEditor.vue'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const router = useRouter()
const toast = useToast()

const announcementId = computed(() => {
  const raw = route.params.id
  return raw ? Number(raw) : null
})
const isEdit = computed(() => announcementId.value !== null && !isNaN(announcementId.value))

const loading = ref(false)
const submitting = ref(false)
const pageError = ref('')

const form = reactive<{
  title: string
  summary: string
  content: string
  isTop: boolean
  status: string
}>({
  title: '',
  summary: '',
  content: '',
  isTop: false,
  status: 'PUBLISHED',
})

async function loadDetail() {
  if (!isEdit.value || announcementId.value === null) return
  loading.value = true
  pageError.value = ''
  try {
    const data = await getAdminAnnouncementDetail(announcementId.value)
    form.title = data.title || ''
    form.summary = data.summary || ''
    form.content = data.content || ''
    form.isTop =
      data.isPinned === 1 ||
      data.isPinned === true ||
      data.isTop === 1 ||
      data.isTop === true
    form.status =
      typeof data.status === 'number'
        ? data.status === 1
          ? 'PUBLISHED'
          : 'DRAFT'
        : data.status || 'PUBLISHED'
  } catch (e) {
    pageError.value = e instanceof ApiError ? e.message : '加载公告详情失败'
  } finally {
    loading.value = false
  }
}

async function handleSubmit(targetStatus: string) {
  if (!form.title.trim()) {
    toast.error('请输入公告标题')
    return
  }
  if (!form.content.trim()) {
    toast.error('请输入公告 Markdown 正文')
    return
  }

  submitting.value = true
  const payload: AnnouncementSavePayload = {
    title: form.title.trim(),
    summary: form.summary.trim() || undefined,
    content: form.content,
    isTop: form.isTop,
    isPinned: form.isTop,
    status: targetStatus,
  }

  try {
    if (isEdit.value && announcementId.value !== null) {
      await updateAdminAnnouncement(announcementId.value, payload)
      toast.success('公告已成功更新')
    } else {
      await createAdminAnnouncement(payload)
      toast.success(targetStatus === 'PUBLISHED' ? '公告已成功发布' : '公告草稿已保存')
    }
    router.push('/admin/announcements')
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '保存失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

onMounted(loadDetail)
</script>

<template>
  <div class="announcement-edit-view container">
    <!-- 顶部导航栏与操作区 -->
    <div class="edit-header">
      <div class="header-left">
        <RouterLink to="/admin/announcements" class="back-link">
          <span class="back-arrow">←</span>
          <span>返回公告运维列表</span>
        </RouterLink>
        <h1 class="page-title">
          {{ isEdit ? `编辑公告 #${announcementId}` : '发布新公告' }}
        </h1>
      </div>

      <div class="header-actions">
        <button
          type="button"
          class="btn btn-ghost btn-sm"
          :disabled="submitting || loading"
          @click="handleSubmit('DRAFT')"
        >
          保存为草稿
        </button>
        <button
          type="button"
          class="btn btn-primary btn-sm"
          :disabled="submitting || loading"
          @click="handleSubmit('PUBLISHED')"
        >
          {{ submitting ? '正在提交…' : (isEdit ? '保存并公开发布' : '立即公开发布') }}
        </button>
      </div>
    </div>

    <!-- 错误横幅 -->
    <p v-if="pageError" class="submit-error">{{ pageError }}</p>

    <!-- 加载中 -->
    <div v-if="loading" class="state-card card">
      <div class="loading-spin"></div>
      <p class="muted">正在加载公告详情…</p>
    </div>

    <!-- 表单卡片 -->
    <form v-else class="edit-form" @submit.prevent>
      <!-- 基本信息卡片 -->
      <section class="form-card card">
        <div class="card-head">
          <span class="card-head-title">基本信息与发布配置</span>
        </div>

        <div class="form-body">
          <div class="form-item">
            <label class="form-label required">公告标题</label>
            <input
              v-model="form.title"
              class="input title-input"
              type="text"
              placeholder="请输入引人注目且规范的公告标题…"
              maxlength="120"
            />
          </div>

          <div class="form-item">
            <label class="form-label">
              导读摘要
              <span class="label-hint">（1~2 行简明概述，若留空将在大厅自动提取正文首部）</span>
            </label>
            <textarea
              v-model="form.summary"
              class="summary-textarea"
              placeholder="用于在大厅看板卡片居中展示的简明导读…"
              rows="2"
              maxlength="300"
            ></textarea>
          </div>

          <div class="form-item-inline">
            <label class="checkbox-container">
              <input v-model="form.isTop" type="checkbox" class="checkbox-input" />
              <span class="checkbox-label">
                <strong>设为置顶公告</strong>
                <span class="label-desc">（置顶公告将固定出现在公告栏最前列，并展示黑色直角 [置顶] 徽标）</span>
              </span>
            </label>
          </div>
        </div>
      </section>

      <!-- Markdown 正文卡片与实时双栏预览 -->
      <section class="form-card card editor-card">
        <div class="card-head">
          <span class="card-head-title">公告 Markdown 正文</span>
          <span class="card-head-sub">支持 LaTeX 数学公式 ($...$ / $$...$$) 与带代码块复制语法</span>
        </div>

        <div class="form-body editor-body">
          <MarkdownEditor
            v-model="form.content"
            placeholder="输入公告正文，左侧编辑，右侧实时直角工业渲染预览…"
          />
        </div>
      </section>

      <!-- 底部操作按钮栏 -->
      <div class="form-bottom-bar">
        <button
          type="button"
          class="btn btn-ghost"
          :disabled="submitting || loading"
          @click="router.push('/admin/announcements')"
        >
          取消
        </button>
        <div class="right-buttons">
          <button
            type="button"
            class="btn btn-ghost"
            :disabled="submitting || loading"
            @click="handleSubmit('DRAFT')"
          >
            保存为草稿
          </button>
          <button
            type="button"
            class="btn btn-primary"
            :disabled="submitting || loading"
            @click="handleSubmit('PUBLISHED')"
          >
            {{ submitting ? '提交中…' : (isEdit ? '保存并公开发布' : '立即公开发布') }}
          </button>
        </div>
      </div>
    </form>
  </div>
</template>

<style scoped>
.announcement-edit-view {
  padding-top: 20px;
  padding-bottom: 72px;
}

.edit-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
  flex-wrap: wrap;
}

.header-left {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.back-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--ink-secondary);
  transition: color 0.15s ease;
  user-select: none;
}

.back-link:hover {
  color: var(--ink);
}

.back-arrow {
  font-size: 14px;
}

.page-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: var(--ink);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 表单卡片 */
.edit-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.form-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 0;
  overflow: hidden;
}

.card-head {
  padding: 12px 20px;
  background: var(--bg);
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
}

.card-head-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--ink);
  letter-spacing: -0.2px;
}

.card-head-sub {
  font-size: 12px;
  color: var(--ink-tertiary);
}

.form-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  background: #ffffff;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-label {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink);
}

.form-label.required::after {
  content: ' *';
  color: var(--danger);
}

.label-hint {
  font-size: 12px;
  font-weight: normal;
  color: var(--ink-tertiary);
  margin-left: 4px;
}

.title-input {
  height: 42px;
  font-size: 15px;
  font-weight: 500;
  background: #ffffff;
}

.summary-textarea {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-radius: 0;
  background: #ffffff;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.5;
  color: var(--ink);
  outline: none;
  resize: vertical;
  transition: border-color 0.15s ease;
}

.summary-textarea:focus {
  border-color: var(--ink);
}

.summary-textarea::placeholder {
  color: var(--ink-tertiary);
}

.form-item-inline {
  padding-top: 4px;
}

.checkbox-container {
  display: inline-flex;
  align-items: flex-start;
  gap: 10px;
  cursor: pointer;
  user-select: none;
}

.checkbox-input {
  margin-top: 3px;
  width: 16px;
  height: 16px;
  border-radius: 0;
  cursor: pointer;
  accent-color: var(--ink);
}

.checkbox-label {
  font-size: 13.5px;
  color: var(--ink);
  line-height: 1.5;
}

.label-desc {
  font-size: 12px;
  color: var(--ink-tertiary);
}

/* 编辑器卡片 */
.editor-body {
  padding: 20px;
}

/* 底部操作条 */
.form-bottom-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 0;
}

.right-buttons {
  display: flex;
  align-items: center;
  gap: 12px;
}
</style>
