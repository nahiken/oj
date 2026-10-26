import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * The one browser-level test (P7 ticket 10).
 *
 * It walks the real chain: login form -> user service -> cookie -> gateway ->
 * ai -> SSE -> drawer. Nothing here mocks the happy path; only the 401
 * case is stubbed, because a real token expiry cannot be produced on demand
 * without waiting out the TTL.
 */

// The frontend package is `"type": "module"`, so `__dirname` does not exist.
const HERE = path.dirname(fileURLToPath(import.meta.url))
const EMAIL = process.env.P7_EMAIL ?? 'p7-playwright@example.invalid'
const PASSWORD = JSON.parse(
  readFileSync(path.resolve(HERE, '../../../scripts/p7_browser_account.json'), 'utf8'),
).password
const SCREENSHOT_DIR = process.env.P7_SCREENSHOT_DIR
  ?? path.resolve(HERE, '../../../docs/generated')

async function login(page: Page) {
  await page.goto('/login')
  // Both tabs render an email box; the account form's placeholder is unique.
  await page.locator('input[placeholder="请输入邮箱(1716607668@qq.com)"]').fill(EMAIL)
  await page.locator('input[placeholder="请输入密码(12345678)"]').fill(PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.waitForURL('**/problemset', { timeout: 20_000 })
}

async function openTutor(page: Page) {
  await page.goto('/problem/1')
  await page.getByRole('button', { name: 'AI 辅导' }).click()
  await expect(page.locator('.tutor-answer')).toBeVisible()
}

async function ask(page: Page, question: string) {
  // The page has several textareas (Monaco, the custom-input box, the drawer);
  // the drawer's placeholder is the only stable one.
  await page.locator('textarea[placeholder^="例如"]').fill(question)
  await page.getByRole('button', { name: '发送', exact: true }).click()
}

test('drawer streams, stops, and never upgrades the level by itself', async ({ page }) => {
  // D3: the meta frame must be inspectable without guessing.
  const consoleLines: string[] = []
  page.on('console', msg => consoleLines.push(msg.text()))

  await login(page)
  await openTutor(page)

  // Three explicit levels, mutually exclusive, each selectable by the user.
  await expect(page.getByRole('radio')).toHaveCount(3)
  for (const label of ['思路', '伪代码', '局部代码']) {
    await page.getByText(label, { exact: true }).click()
    await expect(page.getByRole('radio', { name: label })).toBeChecked()
  }

  await ask(page, '我卡在边界条件，不知道怎么判断')
  const answer = page.locator('.tutor-answer .markdown')
  await expect.poll(() => consoleLines.some(line => line.includes('[tutor] meta'))).toBe(true)

  // The stop button exists for the whole stream. Check it before waiting for the
  // answer so a fast provider cannot make this assertion race the end of the
  // stream (the fake provider is started with P6_FAKE_DELAY_MS for the same
  // reason: something has to still be running when we click).
  const stop = page.getByRole('button', { name: '停止' })
  await expect(stop).toBeVisible()
  await expect(answer).not.toBeEmpty()
  // dispatchEvent instead of click(): while the answer grows the button can be
  // reported as "not stable", and waiting for stability loses the race with the
  // end of the stream. The handler under test is the same.
  await stop.dispatchEvent('click')

  // After stopping: no spinner, the input is usable again, and the text that did
  // arrive stays on screen (it must not clear itself).
  await expect(stop).toBeHidden()
  await expect(page.locator('textarea[placeholder^="例如"]')).toBeEnabled()
  const afterStop = (await answer.innerText()).length
  expect(afterStop).toBeGreaterThan(0)
  await page.waitForTimeout(600)
  expect((await answer.innerText()).length).toBe(afterStop)

  // Switching the level is a user action; sending must not move it.
  // The real <input type=radio> is visually hidden; the label is what a user
  // clicks, so the test clicks the same thing.
  await page.getByText('伪代码', { exact: true }).click()
  await expect(page.getByRole('radio', { name: '伪代码' })).toBeChecked()
  await ask(page, '那给我看看伪代码')
  await expect(answer.locator('pre.tutor-code')).toBeVisible()
  // Sending must not move the level on its own.
  await expect(page.getByRole('radio', { name: '伪代码' })).toBeChecked()

  // D2: a code block can be copied as code, not as rendered text.
  await answer.locator('.tutor-copy').first().click()
  const copied = await page.evaluate(() => navigator.clipboard.readText())
  expect(copied.trim().length).toBeGreaterThan(0)

  await page.screenshot({
    path: path.join(SCREENSHOT_DIR, 'p7-playwright-tutor.png'),
    fullPage: false,
  })
})

test('rate limiting shows a countdown instead of a dead button', async ({ page }) => {
  await login(page)
  await openTutor(page)

  // Burn the per-uid budget through the same gateway the UI uses. The limit is
  // read from the service so the test does not hardcode 6.
  const metrics = await (await page.request.get('/api/ai/metrics')).json()
  const limit = Number(metrics?.limits?.uidPerMin ?? 6)
  const body = {
    problemId: 1,
    level: 1,
    message: 'burn a token',
    history: [],
    sessionId: 'p7-playwright-burn',
  }
  // Fire them together on purpose: one at a time lets the token bucket refill
  // while the fake provider streams (6/min = one token every 10 s), so the
  // "first 429" would arrive after a token had already come back.
  const statuses = await Promise.all(
    Array.from({ length: limit + 2 }, async () => {
      const response = await page.request.post('/api/ai/chat', { data: body })
      await response.text()
      return response.status()
    }),
  )
  expect(statuses, 'the AI must enforce the per-uid limit').toContain(429)

  // The bucket refills one token per (60s / limit) — 10s at the default 6/min.
  // Reading the burn responses can take that long on a busy machine, so the
  // single `ask` below used to slip through on a freshly refilled token and the
  // drawer never rendered an error. Drain whatever came back right before
  // asking: two requests leave the bucket empty either way, and a token cannot
  // refill again within the ~1s this takes.
  await Promise.all(
    Array.from({ length: 2 }, async () => {
      const response = await page.request.post('/api/ai/chat', { data: body })
      await response.text()
    }),
  )

  await ask(page, '再问一次')
  await expect(page.locator('.tutor-error')).toContainText('提问太频繁')
  await expect(page.locator('.tutor-error')).toContainText(/还需等待 \d+ 秒/)
  await expect(page.getByRole('button', { name: '发送', exact: true })).toBeDisabled()
})

test('an invalid session is reported as "log in again", not silence', async ({ page }) => {
  await login(page)
  await openTutor(page)

  // A real logout removes the token from Redis, so the next call is a real 401.
  // It has to happen with the drawer already open: navigation would redirect to
  // /login and there would be no drawer to test.
  const logout = await page.request.post('/api/user/logout')
  expect(logout.ok()).toBeTruthy()

  await ask(page, '还能问吗')
  await expect(page.locator('.tutor-error')).toContainText('登录已过期')
  await expect(page.locator('.tutor-error').getByText('去登录')).toBeVisible()
})
