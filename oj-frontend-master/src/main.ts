import ArcoVue from '@arco-design/web-vue'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import './assets/index.css'

import '@/api/common/interceptor'
import App from './App.vue'
import router from './router'

// Mock user data is opt-in: `pnpm dev` talks to the real gateway unless
// VITE_MOCK_USER=1. A mocked /user/current made the app look logged in while the
// chat endpoint still answered 401, which hid the real login state.
if (import.meta.env.MODE === 'development' && import.meta.env.VITE_MOCK_USER === '1') {
  await import('./mock')
}

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ArcoVue)

app.mount('#app')
