import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Prism from 'prismjs'
import App from './App.vue'
import router from './router'
import './style.css'

/** Prism 语言包是 UMD 模块；分包时须在其加载前暴露同一核心实例。 */
;(globalThis as typeof globalThis & { Prism?: typeof Prism }).Prism = Prism

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
