import { fileURLToPath, URL } from 'node:url'

import { vitePluginForArco } from '@arco-plugins/vite-vue'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'
import monacoEditorPluginModule from 'vite-plugin-monaco-editor'
import vueDevTools from 'vite-plugin-vue-devtools'

const isObjectWithDefaultFunction = (
  module: unknown,
): module is { default: typeof monacoEditorPluginModule } =>
  module != null &&
  typeof module === 'object' &&
  'default' in module &&
  typeof module.default === 'function'

const monacoEditorPlugin = isObjectWithDefaultFunction(monacoEditorPluginModule)
  ? monacoEditorPluginModule.default
  : monacoEditorPluginModule

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
    vitePluginForArco({
      style: 'css',
    }),
    monacoEditorPlugin({
      languageWorkers: ['editorWorkerService'],
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '0.0.0.0',
    port: 8002,
    // Dev requests use relative `/api/...` URLs (the interceptor sets baseURL to
    // '/api' in development). Without this proxy Vite would answer those routes
    // itself with index.html, and every call would look like a 200 HTML page.
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8024',
        changeOrigin: true,
      },
    },
  },
})
