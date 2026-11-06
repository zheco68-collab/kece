import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Prism from 'prismjs'
import App from './App.vue'
import router from './router'
import './style.css'
import { authChannel } from './api/request'
import { useUserStore } from './stores/user'

/** Prism 语言包是 UMD 模块；分包时须在其加载前暴露同一核心实例。 */
;(globalThis as typeof globalThis & { Prism?: typeof Prism }).Prism = Prism

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)

function handleCrossTabLogout() {
  const userStore = useUserStore(pinia)
  if (userStore.isLoggedIn) {
    userStore.clearAuth()
    if (router.currentRoute.value.path !== '/login') {
      router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
    }
  }
}

if (authChannel) {
  authChannel.onmessage = (event) => {
    if (event?.data?.type === 'LOGOUT') {
      handleCrossTabLogout()
    }
  }
}

if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event) => {
    if (event.key === 'kece:logout_event' && event.newValue) {
      handleCrossTabLogout()
    }
  })
}

app.mount('#app')
