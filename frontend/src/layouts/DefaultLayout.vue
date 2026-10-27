<script setup lang="ts">
import { RouterView } from 'vue-router'
import AppHeader from '@/components/AppHeader.vue'
import ConfirmModal from '@/components/ConfirmModal.vue'
import ToastHost from '@/components/ToastHost.vue'
</script>

<template>
  <div class="layout">
    <AppHeader />
    <main class="main">
      <RouterView />
    </main>
    <!-- 全局提示与确认框宿主：各页面共用同一份单例状态。 -->
    <ToastHost />
    <ConfirmModal />
    <footer class="footer">KECE 课测 · 多维度编程与技能评测平台</footer>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}
.main {
  flex: 1;
  width: 100%;
}
.footer {
  border-top: 1px solid var(--line);
  padding: 24px;
  text-align: center;
  font-size: 13px;
  color: var(--ink-tertiary);
}
</style>
