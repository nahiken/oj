/**
 * Guided launcher for the 12 single-person sessions (P7 ticket 11).
 *
 * It does the mechanical parts a human should not have to repeat: log in as a
 * pre-seeded uid, inject the cookie, and open the problem page. The human still
 * does the part that makes the sample meaningful: read the hint, decide whether
 * it helped, and submit the WA / AC code by their own judgement.
 *
 *   node scripts/solo-session.mjs --uid 9900000101          # headed browser
 *   node scripts/solo-session.mjs --uid 9900000101 --headless   # CI-style check
 *
 * Prerequisite: the uid was seeded with scripts/seed_p7_browser_user.py and the
 * dev server (8002), gateway (8024) and AI (8026) are up.
 */

import { createRequire } from 'node:module'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(HERE, '..')
const require = createRequire(path.join(ROOT, 'oj-frontend-master/package.json'))
const { chromium } = require('@playwright/test')

const PASSWORD = JSON.parse(
  readFileSync(path.join(ROOT, 'scripts/p7_browser_account.json'), 'utf8'),
).password

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`)
  if (index === -1 || index + 1 >= process.argv.length) {
    return fallback
  }
  return process.argv[index + 1]
}

const uid = Number(arg('uid', '0'))
const base = arg('base', 'http://localhost:8002')
const headless = process.argv.includes('--headless')

if (!uid) {
  console.error('usage: node scripts/solo-session.mjs --uid <uid> [--headless] [--base http://localhost:8002]')
  process.exit(2)
}

const email = `p7-solo-${uid}@example.invalid`

async function tokenForUid() {
  const response = await fetch('http://localhost:8024/api/user/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: PASSWORD }),
  })
  const body = await response.json()
  if (body.code !== 200) {
    throw new Error(`login failed for ${email}: ${response.status} ${JSON.stringify(body)}`)
  }
  const cookie = response.headers.getSetCookie()
    .map(value => value.split(';', 1)[0])
    .find(value => value.startsWith('token='))
  if (!cookie) {
    throw new Error('login did not set a token cookie')
  }
  return cookie.slice('token='.length)
}

const token = await tokenForUid()
const browser = await chromium.launch({ headless })
const context = await browser.newContext()
await context.addCookies([
  { name: 'token', value: token, domain: 'localhost', path: '/', httpOnly: false },
])
const page = await context.newPage()
await page.goto(`${base}/problem/1`, { waitUntil: 'domcontentloaded' })

if (headless) {
  await page.getByRole('button', { name: 'AI 辅导' }).waitFor({ timeout: 20_000 })
  console.log(`headless check ok: ${email} can open the problem page and sees AI 辅导`)
  await browser.close()
  process.exit(0)
}

console.log('')
console.log(`signed in as ${email} (uid ${uid}) on ${base}/problem/1`)
console.log('')
console.log('Do one single-person session:')
console.log('  1. submit the WA code   benchmark/fixtures/p7-wa-submit.json, wait for WA')
console.log('  2. open AI 辅导, ask about the problem, read the hint')
console.log('  3. submit the AC code   benchmark/fixtures/p7-ac-submit.json within 30 minutes')
console.log('  4. close this browser window when the result is final')
console.log('')
console.log('When all 12 uids are done:')
console.log('  D:\\python\\python.exe benchmark/p7_second_ac.py --uid-from 9900000101 --uid-to 9900000112 \\')
console.log('      --preflight')
console.log('  D:\\python\\python.exe benchmark/p7_second_ac.py --uid-from 9900000101 --uid-to 9900000112 \\')
console.log('      --driver single-person --out benchmark/results/p7-second-ac-solo.json')

// Closing the window closes the page, not necessarily the context; wait for
// either so the launcher does not hang after the human finishes.
await Promise.race([
  new Promise(resolve => context.on('close', resolve)),
  page.waitForEvent('close'),
])
await browser.close().catch(() => {})
