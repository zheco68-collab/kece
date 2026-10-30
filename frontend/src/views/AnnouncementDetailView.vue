<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { getAnnouncementDetail, type AnnouncementDetail } from '@/api/announcement'
import { ApiError } from '@/api/request'
import { copyToClipboard } from '@/utils/clipboard'
import { renderMarkdown } from '@/utils/markdown'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const router = useRouter()
const toast = useToast()

const announcementId = computed(() => Number(route.params.id))
const loading = ref(false)
const loadError = ref('')
const detail = ref<AnnouncementDetail | null>(null)
const contentRef = ref<HTMLElement | null>(null)

function formatDateTime(str?: string): string {
  if (!str) return '-'
  const d = new Date(str)
  if (isNaN(d.getTime())) return str
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function isPinned(item: AnnouncementDetail | null): boolean {
  if (!item) return false
  return (
    item.isPinned === 1 ||
    item.isPinned === true ||
    item.isTop === 1 ||
    item.isTop === true
  )
}

const renderedContent = computed(() => {
  if (!detail.value?.content) return ''
  return renderMarkdown(detail.value.content)
})

function enhanceCodeBlocks() {
  if (!contentRef.value) return
  const preElements = contentRef.value.querySelectorAll('pre')
  preElements.forEach((pre) => {
    if (pre.querySelector('.code-copy-btn')) return
    pre.style.position = 'relative'

    const btn = document.createElement('button')
    btn.type = 'button'
    btn.className = 'code-copy-btn'
    btn.innerText = '复制'
    btn.title = '复制代码'
    btn.setAttribute('aria-label', '复制代码')

    btn.addEventListener('click', async (e) => {
      e.stopPropagation()
      const codeEl = pre.querySelector('code')
      const code = codeEl ? codeEl.innerText : pre.innerText
      const ok = await copyToClipboard(code)
      if (ok) {
        btn.innerText = '已复制'
        btn.classList.add('copied')
        setTimeout(() => {
          btn.innerText = '复制'
          btn.classList.remove('copied')
        }, 2000)
      }
    })

    pre.appendChild(btn)
  })
}

async function loadDetail() {
  if (isNaN(announcementId.value)) {
    loadError.value = '无效的公告 ID'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    detail.value = await getAnnouncementDetail(announcementId.value)
    await nextTick()
    enhanceCodeBlocks()
  } catch (err) {
    loadError.value = err instanceof ApiError ? err.message : '获取公告详情失败'
  } finally {
    loading.value = false
  }
}

async function handleCopyLink() {
  const url = window.location.href
  const ok = await copyToClipboard(url)
  if (ok) {
    toast.success('公告链接已复制到剪贴板')
  } else {
    toast.error('复制失败，请手动选取浏览器地址栏链接')
  }
}

watch(renderedContent, () => {
  nextTick(() => {
    enhanceCodeBlocks()
  })
})

onMounted(loadDetail)
</script>

<template>
  <div class="announcement-detail-view container">
    <!-- 顶部唯一标准返回导航：严格只在顶部保留一个返回按钮 -->
    <nav class="top-nav">
      <RouterLink to="/announcements" class="back-link">
        <span class="back-arrow">←</span>
        <span>返回公告栏</span>
      </RouterLink>
    </nav>

    <!-- 加载中 -->
    <div v-if="loading" class="state-card card">
      <div class="loading-spin"></div>
      <p class="muted">正在加载公告详情…</p>
    </div>

    <!-- 加载错误 -->
    <div v-else-if="loadError" class="error-wrap card">
      <p class="submit-error">{{ loadError }}</p>
      <button class="btn btn-ghost btn-sm" type="button" @click="router.push('/announcements')">
        返回列表
      </button>
    </div>

    <!-- 公告正文全宽阅读画布 -->
    <article v-else-if="detail" class="detail-article">
      <!-- 头部：大字号标题 + 属性 + 元信息 -->
      <header class="detail-header card">
        <div class="header-title-row">
          <span v-if="isPinned(detail)" class="pin-badge">置顶</span>
          <h1 class="main-title">{{ detail.title }}</h1>
        </div>

        <div class="detail-meta-bar">
          <span class="meta-item">
            <span class="meta-label">发布管理员:</span>
            <span class="meta-value author">{{ detail.authorName || 'admin' }}</span>
          </span>
          <span class="meta-sep">/</span>
          <span class="meta-item">
            <span class="meta-label">发布时间:</span>
            <span class="meta-value mono">{{ formatDateTime(detail.createdAt) }}</span>
          </span>
          <span class="meta-sep">/</span>
          <span class="meta-item">
            <span class="meta-label">阅读量:</span>
            <span class="meta-value mono">{{ detail.viewCount ?? 0 }}</span>
          </span>
        </div>
      </header>

      <!-- 全宽阅读画布，调用 renderMarkdown(content) 渲染正文 -->
      <section class="reading-canvas card">
        <!-- eslint-disable-next-line vue/no-v-html -- 经过 renderMarkdown 与 XSS 清洗 -->
        <div ref="contentRef" class="prose reading-body" v-html="renderedContent"></div>
      </section>

      <!-- 底部：仅保留版权信息与复制公告链接按钮（不重复出现返回按钮） -->
      <footer class="detail-footer">
        <div class="footer-inner card">
          <div class="copyright-info">
            <span class="copyright-tag mono">COPYRIGHT</span>
            <span class="copyright-text">
              © KECE 课测平台运维中心 · 本公告最终解释权归平台管理委员会所有
            </span>
          </div>
          <button
            type="button"
            class="copy-link-btn"
            title="复制本公告链接"
            @click="handleCopyLink"
          >
            <svg class="link-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
              <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
            </svg>
            <span>复制公告链接</span>
          </button>
        </div>
      </footer>
    </article>
  </div>
</template>

<style scoped>
.announcement-detail-view {
  padding-top: 24px;
  padding-bottom: 72px;
  max-width: 1040px;
}

/* 顶部唯一标准返回导航 */
.top-nav {
  margin-bottom: 20px;
}

.back-link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  font-size: 14px;
  font-weight: 500;
  color: var(--ink-secondary);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 0;
  transition:
    color 0.15s ease,
    border-color 0.15s ease,
    background-color 0.15s ease;
  user-select: none;
}

.back-link:hover {
  color: var(--ink);
  border-color: var(--ink);
  background: #ffffff;
}

.back-arrow {
  font-size: 15px;
  line-height: 1;
}

.error-wrap {
  padding: 32px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}

/* 详情文章整体 */
.detail-article {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 头部卡片 */
.detail-header {
  padding: 28px 32px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 0;
  box-shadow: none;
}

.header-title-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 16px;
}

.pin-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 24px;
  padding: 0 8px;
  background: var(--ink);
  color: #ffffff;
  font-size: 11px;
  font-weight: 700;
  border-radius: 0;
  letter-spacing: 0.5px;
  margin-top: 5px;
  flex-shrink: 0;
  user-select: none;
}

.main-title {
  margin: 0;
  font-size: 26px;
  font-weight: 700;
  line-height: 1.35;
  color: var(--ink);
  letter-spacing: -0.4px;
  word-break: break-word;
}

.detail-meta-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 13px;
  color: var(--ink-tertiary);
  border-top: 1px solid var(--line);
  padding-top: 14px;
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.meta-label {
  color: var(--ink-tertiary);
}

.meta-value {
  color: var(--ink-secondary);
  font-weight: 500;
}

.meta-value.author {
  color: var(--ink);
  font-weight: 600;
}

.meta-sep {
  color: var(--line);
}

/* 全宽阅读画布 */
.reading-canvas {
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 0;
  box-shadow: none;
  min-height: 380px;
}

.reading-body {
  padding: 36px 40px;
  font-size: 15px;
  line-height: 1.8;
  color: var(--ink);
  word-break: break-word;
}

/* 增强代码块及复制按钮直角工业设计 */
:deep(.reading-body pre) {
  position: relative;
  background: #1e1e1e;
  color: #f8f9fa;
  border: 1px solid #2d2d2d;
  border-radius: 0;
  padding: 20px 24px;
  overflow-x: auto;
  font-size: 13.5px;
  line-height: 1.6;
}

:deep(.reading-body pre code) {
  background: transparent;
  color: inherit;
  border: none;
  padding: 0;
}

:deep(.reading-body .code-copy-btn) {
  position: absolute;
  top: 10px;
  right: 10px;
  padding: 4px 10px;
  font-size: 11px;
  font-family: var(--font);
  font-weight: 600;
  color: #c9d1d9;
  background: #2b2b2b;
  border: 1px solid #444c56;
  border-radius: 0;
  cursor: pointer;
  opacity: 0.85;
  transition: all 0.15s ease;
  user-select: none;
}

:deep(.reading-body .code-copy-btn:hover) {
  opacity: 1;
  color: #ffffff;
  border-color: #1677ff;
  background: #1677ff;
}

:deep(.reading-body .code-copy-btn.copied) {
  opacity: 1;
  color: #ffffff;
  border-color: #2f9e44;
  background: #2f9e44;
}

/* 底部区域：仅保留版权信息与复制公告链接按钮 */
.detail-footer {
  margin-top: 8px;
}

.footer-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
  padding: 16px 24px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 0;
}

.copyright-info {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--ink-tertiary);
  flex-wrap: wrap;
}

.copyright-tag {
  font-size: 10px;
  font-weight: 700;
  padding: 1px 5px;
  background: var(--line);
  color: var(--ink);
  letter-spacing: 0.5px;
}

.copy-link-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 0;
  cursor: pointer;
  transition:
    color 0.15s ease,
    border-color 0.15s ease,
    background-color 0.15s ease;
  white-space: nowrap;
}

.copy-link-btn:hover {
  border-color: var(--ink);
  background: var(--surface-hover);
}

.link-icon {
  width: 14px;
  height: 14px;
}

@media (max-width: 640px) {
  .detail-header {
    padding: 20px 20px;
  }
  .main-title {
    font-size: 20px;
  }
  .reading-body {
    padding: 20px 16px;
  }
  .footer-inner {
    flex-direction: column;
    align-items: flex-start;
  }
  .copy-link-btn {
    width: 100%;
    justify-content: center;
  }
}
</style>
