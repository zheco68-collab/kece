<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { getAnnouncementDetail, type AnnouncementDetail } from '@/api/announcement';
import { ApiError } from '@/api/request';
import { useToast } from '@/composables/useToast';
import { copyToClipboard } from '@/utils/clipboard';
import { renderMarkdown } from '@/utils/markdown';

const route = useRoute();
const router = useRouter();
const toast = useToast();

const announcementId = computed(() => Number(route.params.id));
const loading = ref(false);
const loadError = ref('');
const detail = ref<AnnouncementDetail | null>(null);
const contentRef = ref<HTMLElement | null>(null);
const copyFeedback = ref('');

let detailRequestId = 0;
let isUnmounted = false;
const copyTimers = new Map<HTMLElement, ReturnType<typeof setTimeout>>();
let feedbackTimer: ReturnType<typeof setTimeout> | null = null;

function clearAllCopyTimers() {
  for (const timerId of copyTimers.values()) {
    clearTimeout(timerId);
  }
  copyTimers.clear();
  if (feedbackTimer) {
    clearTimeout(feedbackTimer);
    feedbackTimer = null;
  }
}

function announceCopyStatus(msg: string) {
  copyFeedback.value = msg;
  if (feedbackTimer) clearTimeout(feedbackTimer);
  feedbackTimer = setTimeout(() => {
    feedbackTimer = null;
    copyFeedback.value = '';
  }, 2000);
}

function formatDateTime(str?: string): string {
  if (!str) return '-';
  const d = new Date(str);
  if (isNaN(d.getTime())) return str;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function isPinned(item: AnnouncementDetail | null): boolean {
  if (!item) return false;
  return item.isPinned === 1 || item.isPinned === true || item.isTop === 1 || item.isTop === true;
}

const renderedContent = computed(() => {
  if (!detail.value?.content) return '';
  return renderMarkdown(detail.value.content);
});

function enhanceCodeBlocks() {
  if (!contentRef.value) return;
  const preElements = contentRef.value.querySelectorAll('pre');
  preElements.forEach((pre) => {
    if (pre.querySelector('.code-copy-btn')) return;
    pre.style.position = 'relative';

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'code-copy-btn';
    btn.innerText = '复制';
    btn.title = '复制代码';
    btn.setAttribute('aria-label', '复制代码');
    btn.setAttribute('aria-live', 'polite');

    btn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const codeEl = pre.querySelector('code');
      const code = codeEl ? codeEl.innerText : pre.innerText;
      const ok = await copyToClipboard(code);
      if (ok && !isUnmounted) {
        const prevTimer = copyTimers.get(btn);
        if (prevTimer) clearTimeout(prevTimer);
        btn.innerText = '已复制';
        btn.classList.add('copied');
        announceCopyStatus('代码已复制到剪贴板');
        const timerId = setTimeout(() => {
          copyTimers.delete(btn);
          btn.innerText = '复制';
          btn.classList.remove('copied');
        }, 2000);
        copyTimers.set(btn, timerId);
      }
    });

    pre.appendChild(btn);
  });
}

async function loadDetail() {
  const currentId = announcementId.value;
  if (!Number.isFinite(currentId) || currentId <= 0) {
    loading.value = false;
    loadError.value = '无效的公告 ID';
    return;
  }
  const reqId = ++detailRequestId;
  loading.value = true;
  loadError.value = '';
  try {
    const res = await getAnnouncementDetail(currentId);
    if (isUnmounted || reqId !== detailRequestId) return;
    detail.value = res;
    await nextTick();
    if (!isUnmounted && reqId === detailRequestId) {
      enhanceCodeBlocks();
    }
  } catch (err) {
    if (!isUnmounted && reqId === detailRequestId) {
      loadError.value = err instanceof ApiError ? err.message : '获取公告详情失败';
    }
  } finally {
    if (!isUnmounted && reqId === detailRequestId) {
      loading.value = false;
    }
  }
}

async function handleCopyLink() {
  const url = window.location.href;
  const ok = await copyToClipboard(url);
  if (ok) {
    announceCopyStatus('公告链接已复制到剪贴板');
    toast.success('公告链接已复制到剪贴板');
  } else {
    announceCopyStatus('复制链接失败');
    toast.error('复制失败，请手动选取浏览器地址栏链接');
  }
}

watch(renderedContent, () => {
  void nextTick(() => {
    if (!isUnmounted) {
      enhanceCodeBlocks();
    }
  });
});

watch(
  () => route.params.id,
  () => {
    clearAllCopyTimers();
    void loadDetail();
  },
);

onMounted(() => {
  void loadDetail();
});

onBeforeUnmount(() => {
  isUnmounted = true;
  detailRequestId++;
  clearAllCopyTimers();
});
</script>

<template>
  <div class="announcement-detail-view container tabular-nums">
    <!-- 顶部唯一标准返回导航 -->
    <nav class="top-nav" aria-label="面包屑导航">
      <RouterLink to="/announcements" class="back-link">
        <span class="back-arrow" aria-hidden="true">←</span>
        <span>返回公告栏</span>
      </RouterLink>
    </nav>

    <!-- 屏幕阅读器无障碍复制状态播报 -->
    <span class="sr-only" aria-live="polite">{{ copyFeedback }}</span>

    <!-- 加载中 -->
    <div v-if="loading" class="state-card card" role="status">
      <div class="loading-spin" aria-hidden="true"></div>
      <p class="muted">正在加载公告详情…</p>
    </div>

    <!-- 加载错误 -->
    <div v-else-if="loadError" class="error-wrap card" role="alert">
      <p class="submit-error">{{ loadError }}</p>
      <div class="error-actions">
        <button class="btn btn-primary btn-sm" type="button" @click="loadDetail">重试</button>
        <button class="btn btn-ghost btn-sm" type="button" @click="router.push('/announcements')">
          返回列表
        </button>
      </div>
    </div>

    <!-- 公告正文全宽阅读画布 -->
    <article v-else-if="detail" class="detail-article">
      <!-- 头部：大字号标题 + 属性 + 元信息 -->
      <header class="detail-header card">
        <div class="header-title-row">
          <span v-if="isPinned(detail)" class="hud-pill tone-teal pin-badge">置顶</span>
          <h1 class="main-title">{{ detail.title }}</h1>
        </div>

        <div class="detail-meta-bar">
          <span class="meta-item">
            <span class="meta-label">发布管理员:</span>
            <span class="meta-value author">{{ detail.authorName || 'admin' }}</span>
          </span>
          <span class="meta-sep" aria-hidden="true">/</span>
          <span class="meta-item">
            <span class="meta-label">发布时间:</span>
            <span class="meta-value mono tabular-nums">{{ formatDateTime(detail.createdAt) }}</span>
          </span>
          <span class="meta-sep" aria-hidden="true">/</span>
          <span class="meta-item">
            <span class="meta-label">阅读量:</span>
            <span class="meta-value mono tabular-nums">{{ detail.viewCount ?? 0 }}</span>
          </span>
        </div>
      </header>

      <!-- 全宽阅读画布，调用 renderMarkdown(content) 渲染正文 -->
      <section class="reading-canvas card">
        <!-- eslint-disable-next-line vue/no-v-html -- 经过 renderMarkdown 与 XSS 清洗 -->
        <div ref="contentRef" class="prose reading-body" v-html="renderedContent"></div>
      </section>

      <!-- 底部：仅保留版权信息与复制公告链接按钮 -->
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
            <svg
              class="link-icon"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              aria-hidden="true"
            >
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
  background: var(--kece-surface-muted);
  border: 1px solid var(--kece-border);
  border-radius: 0;
  transition:
    color 0.15s ease,
    border-color 0.15s ease,
    background-color 0.15s ease;
  user-select: none;
}

.back-link:hover {
  color: var(--kece-accent-ink);
  border-color: var(--kece-accent);
  background: var(--kece-accent-soft);
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

.error-actions {
  display: inline-flex;
  align-items: center;
  gap: 10px;
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
  background: var(--kece-surface-muted);
  border: 1px solid var(--kece-border);
  border-top: 2px solid var(--kece-accent);
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
  margin-top: 4px;
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
  border-top: 1px solid var(--kece-border);
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
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
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

/* 干净浅色实验室代码块底，消除与全局 .prose token 颜色冲突及右上角标签重叠 */
:deep(.reading-body pre) {
  position: relative;
  background: var(--kece-surface-muted);
  color: var(--ink);
  border: 1px solid var(--kece-border);
  border-radius: 0;
  padding: 40px 20px 18px;
  overflow-x: auto;
  font-size: 13.5px;
  line-height: 1.65;
}

:deep(.reading-body pre code) {
  background: transparent;
  color: inherit;
  border: none;
  padding: 0;
}

:deep(.reading-body .code-language) {
  top: 10px;
  left: 16px;
  right: auto;
  color: var(--ink-tertiary);
  font-weight: 600;
}

:deep(.reading-body .token.comment),
:deep(.reading-body .token.prolog),
:deep(.reading-body .token.doctype),
:deep(.reading-body .token.cdata) {
  color: var(--ink-tertiary);
}

:deep(.reading-body .token.boolean),
:deep(.reading-body .token.number) {
  color: #854d0e;
}

:deep(.reading-body .code-copy-btn) {
  position: absolute;
  top: 6px;
  right: 10px;
  padding: 4px 10px;
  font-size: 12px;
  font-family: var(--font);
  font-weight: 600;
  color: var(--ink-secondary);
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  border-radius: 0;
  cursor: pointer;
  transition:
    background-color 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease;
  user-select: none;
}

:deep(.reading-body .code-copy-btn:hover) {
  color: #ffffff;
  border-color: var(--kece-accent);
  background: var(--kece-accent);
}

:deep(.reading-body .code-copy-btn.copied) {
  color: #ffffff;
  border-color: var(--kece-success);
  background: var(--kece-success);
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
  background: var(--kece-surface-muted);
  border: 1px solid var(--kece-border);
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
  background: var(--kece-border);
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
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  border-radius: 0;
  cursor: pointer;
  transition:
    color 0.15s ease,
    border-color 0.15s ease,
    background-color 0.15s ease;
  white-space: nowrap;
}

.copy-link-btn:hover {
  border-color: var(--kece-accent);
  color: var(--kece-accent-ink);
  background: var(--kece-accent-soft);
}

.link-icon {
  width: 14px;
  height: 14px;
}

@media (prefers-reduced-motion: reduce) {
  .back-link,
  .copy-link-btn,
  :deep(.reading-body .code-copy-btn) {
    transition: none;
  }
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
