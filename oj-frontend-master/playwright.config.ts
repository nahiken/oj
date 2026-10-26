import { defineConfig } from '@playwright/test'

// The browser test drives the real stack: Vite -> gateway -> ai -> judge.
// `localhost` (not 127.0.0.1) matters: the user service sets the token cookie
// with domain=localhost, and a mismatched host silently drops it.
export default defineConfig({
  testDir: './tests/e2e',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  outputDir: '../.runtime/playwright',
  use: {
    baseURL: process.env.P7_BASE_URL || 'http://localhost:8002',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    // Needed by the "copy code block" assertion in tutor.spec.ts.
    permissions: ['clipboard-read', 'clipboard-write'],
  },
})
