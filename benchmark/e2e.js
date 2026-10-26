#!/usr/bin/env node
/**
 * 端到端判题延迟测量：提交后轮询状态，统计「入队 → 终态」延迟。
 *
 * 环境变量：
 *   BASE_URL       默认 http://127.0.0.1:8024
 *   COOKIE         必填，形如 token=xxx（读取 .runtime/cookie.txt）
 *   BODY_FILE      默认 .runtime/submit-body.json
 *   N              提交次数，默认 20
 *   SUBMIT_CONCURRENCY 提交并发，默认 4
 *   POLL_MS        轮询间隔，默认 300
 *   TIMEOUT_MS     单次等待上限，默认 180000
 *   LABEL          场景名
 *   OUTPUT         结果 JSON 路径
 *
 * 输出：提交延迟、端到端延迟、终态分布。
 */
'use strict';

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');

const cfg = {
  baseUrl: process.env.BASE_URL || 'http://127.0.0.1:8024',
  cookie: process.env.COOKIE || '',
  bodyFile: process.env.BODY_FILE || '.runtime/submit-body.json',
  n: Number(process.env.N || 20),
  submitConcurrency: Number(process.env.SUBMIT_CONCURRENCY || 4),
  pollMs: Number(process.env.POLL_MS || 300),
  timeoutMs: Number(process.env.TIMEOUT_MS || 180000),
  label: process.env.LABEL || 'e2e',
  output: process.env.OUTPUT || '',
};

const base = new URL(cfg.baseUrl);
const lib = base.protocol === 'https:' ? https : http;
const body = fs.readFileSync(cfg.bodyFile, 'utf8');

function request(method, pathname, payload) {
  return new Promise((resolve, reject) => {
    const started = process.hrtime.bigint();
    const req = lib.request(
      {
        hostname: base.hostname,
        port: base.port || 80,
        path: pathname,
        method,
        headers: {
          'Content-Type': 'application/json',
          Cookie: cfg.cookie,
          ...(payload ? { 'Content-Length': Buffer.byteLength(payload) } : {}),
        },
      },
      (res) => {
        let data = '';
        res.setEncoding('utf8');
        res.on('data', (c) => (data += c));
        res.on('end', () => {
          const latencyMs = Number(process.hrtime.bigint() - started) / 1e6;
          let json = null;
          try {
            json = JSON.parse(data);
          } catch (_) {
            /* 非 JSON，保持 null */
          }
          // 雪花 ID 超出 JS 安全整数（2^53），必须从原文取字符串；后端按字符串序列化
          const idMatch = typeof data === 'string' ? data.match(/"id"\s*:\s*"?(\d{10,})"?/) : null;
          resolve({ status: res.statusCode, json, raw: data, id: idMatch ? idMatch[1] : null, latencyMs });
        });
      }
    );
    req.setTimeout(30000, () => req.destroy(new Error('request timeout')));
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

function percentile(values, p) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return Number(sorted[idx].toFixed(1));
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function submitOne() {
  const started = process.hrtime.bigint();
  const res = await request('POST', '/api/judge/submit', body);
  const submitMs = Number(process.hrtime.bigint() - started) / 1e6;
  if (res.status !== 200 || !res.json || res.json.code !== 200 || !res.json.data) {
    return { ok: false, submitMs, error: res.json ? `${res.json.code}:${res.json.msg}` : `http ${res.status}` };
  }
  return { ok: true, submitMs, id: res.id };
}

async function waitTerminal(id) {
  const deadline = Date.now() + cfg.timeoutMs;
  while (Date.now() < deadline) {
    await sleep(cfg.pollMs);
    const res = await request('GET', `/api/judge/submit/${id}/status`, null);
    if (res.status === 200 && res.json && res.json.code === 200 && res.json.data) {
      if (res.json.data.status >= 2) {
        return { status: res.json.data.status, judgeInfoStatus: res.json.data.judgeInfo?.status ?? null };
      }
    }
  }
  return { status: -1, judgeInfoStatus: null, timeout: true };
}

async function main() {
  if (!cfg.cookie) throw new Error('缺少 COOKIE 环境变量，先跑 benchmark/setup.ps1');
  const submissions = [];
  let cursor = 0;
  async function worker() {
    while (cursor < cfg.n) {
      cursor += 1;
      try {
        submissions.push(await submitOne());
      } catch (e) {
        submissions.push({ ok: false, error: e.message, submitMs: 0 });
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(cfg.submitConcurrency, cfg.n) }, worker));

  const accepted = submissions.filter((s) => s.ok);
  const results = await Promise.all(
    accepted.map(async (s) => {
      const started = Date.now();
      const terminal = await waitTerminal(s.id);
      return { id: s.id, submitMs: s.submitMs, e2eMs: Date.now() - started, ...terminal };
    })
  );

  const statusCounts = {};
  const judgeCounts = {};
  for (const r of results) {
    statusCounts[r.status] = (statusCounts[r.status] || 0) + 1;
    const k = r.judgeInfoStatus === null ? 'null' : r.judgeInfoStatus;
    judgeCounts[k] = (judgeCounts[k] || 0) + 1;
  }

  const submitLatencies = submissions.map((s) => s.submitMs);
  const e2eLatencies = results.filter((r) => r.status >= 2).map((r) => r.e2eMs);
  const summary = {
    label: cfg.label,
    target: cfg.baseUrl,
    n: cfg.n,
    submitted: submissions.length,
    accepted: accepted.length,
    failedSubmit: submissions.filter((s) => !s.ok).map((s) => s.error),
    terminal: results.filter((r) => r.status >= 2).length,
    timedOut: results.filter((r) => r.timeout).length,
    submitMs: {
      p50: percentile(submitLatencies, 50),
      p95: percentile(submitLatencies, 95),
      max: percentile(submitLatencies, 100),
    },
    e2eMs: {
      p50: percentile(e2eLatencies, 50),
      p95: percentile(e2eLatencies, 95),
      max: percentile(e2eLatencies, 100),
    },
    statusCounts,
    judgeInfoStatusCounts: judgeCounts,
    startedAt: new Date().toISOString(),
  };
  console.log(JSON.stringify(summary, null, 2));

  if (cfg.output) {
    fs.mkdirSync(path.dirname(cfg.output), { recursive: true });
    fs.writeFileSync(cfg.output, JSON.stringify({ summary, results }, null, 2));
    console.log(`[e2e] written to ${cfg.output}`);
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
