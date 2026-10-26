import { BACKEND_URL } from '@/config/config'
import { Message } from '@arco-design/web-vue'
import type { AxiosResponse } from 'axios'
import axios from 'axios'
import type { R } from './types'

// 开发态同样走 `/api`：Vite 把它代理到网关（见 vite.config.ts 的 server.proxy）。
// 之前开发态 baseURL 为空，`/user/login` 会被 Vite 当成 SPA 路由返回 index.html，
// 请求“成功”但拿不到业务数据。
axios.defaults.baseURL
  = import.meta.env.MODE === 'development' ? '/api' : `${BACKEND_URL}/api`
axios.defaults.withCredentials = true

// 响应拦截器
axios.interceptors.response.use(
  (response: AxiosResponse<R>) => {
    const res = response.data
    if (res.code != 200) {
      // 401 由调用方决定怎么展示（例如路由守卫静默跳到登录页）。
      // 这里再弹一个 "Error" 只会盖住真正的提示。
      if (res.code !== 401) {
        Message.error({
          content: res.msg || 'Error',
          duration: 5 * 1000,
        })
      }
      return Promise.reject(new Error(res.msg || 'Error'))
    }
    return response
  },
  error => {
    if (error?.response?.status !== 401) {
      Message.error({
        content: error.msg || 'Request Error',
        duration: 5 * 1000,
      })
    }
    return Promise.reject(error)
  },
)

export default axios
