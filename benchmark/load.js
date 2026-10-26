#!/usr/bin/env node
/**
 * 零依赖压测器。闭路并发：每个 worker 循环发请求，直到 duration 结束。
 *
 * 环境变量：
 *   BASE_URL      默认 http://127.0.0.1:8024
 *   PATHNAME      默认 /api/judge/submit
 *   METHOD        默认 POST
 *   CONCURRENCY   默认 20
 *   DURATION_SEC  默认 60
 *   WARMUP_SEC    默认 5
 *   TIMEOUT_MS    默认 60000
 *   BODY_FILE     可选，请求体文件
 *   BODY          可选，请求体字符串（BODY_FILE 优先）
 *   COOKIE        可选，Cookie 头
 *   CONTENT_TYPE  默认 application/json
 *   LABEL         场景名，默认 scenario
 *   OUTPUT        可选，结果 JSON 路径
 *   MIN_SUCCESS_RATE 可选，低于该值退出码 1
 */
'use strict';

const http = require('http');
const https = require('https');
const fs = require('fs');
const { URL } = require('url');

function int(name, def) {
  const v = process.env[name];
  return v ? parseInt(v, 10) : def;
}

const cfg = {
  baseUrl: process.env.BASE_URL || 'http://127.0.0.1:8024',
  path: process.env.PATHNAME || '/api/judge/submit',
  method: (process.env.METHOD || 'POST').toUpperCase(),
  concurrency: int('CONCURRENCY', 20),
  durationSec: int('DURATION_SEC', 60),
  warmupSec: int('WARMUP_SEC', 5),
  timeoutMs: int('TIMEOUT_MS', 60000),
  cookie: process.env.COOKIE || '',
  contentType: process.env.CONTENT_TYPE || 'application/json',
  label: process.env.LABEL || 'scenario',
  output: process.env.OUTPUT || '',
  minSuccessRate: process.env.MIN_SUCCESS_RATE ? parseFloat(process.env.MIN_SUCCESS_RATE) : null,
};

function readBody() {
  if (process.env.BODY_FILE) return fs.readFileSync(process.env.BODY_FILE, 'utf8');
  return process.env.BODY || '';
}

const body = readBody();
const url = new URL(cfg.path, cfg.baseUrl);
const lib = url.protocol === 'https:' ? https : http;
const agent = new lib.Agent({ keepAlive: true, maxSockets: cfg.concurrency });

const headers = {
  'content-type': cfg.contentType,
  'content-length': Buffer.byteLength(body),
  connection: 'keep-alive',
};
if (cfg.cookie) headers.cookie = cfg.cookie;

function percentile(sorted, p) {
  if (!sorted.length) return 0;
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[idx];
}

function requestOnce() {
  const started = process.hrtime.bigint();
  return new Promise((resolve) => {
    const req = lib.request(
      {
        protocol: url.protocol,
        hostname: url.hostname,
        port: url.port,
        path: url.pathname + url.search,
        method: cfg.method,
        agent,
        headers,
      },
      (res) => {
        let bytes = 0;
        res.on('data', (chunk) => { bytes += chunk.length; });
        res.on('end', () => {
          const ms = Number(process.hrtime.bigint() - started) / 1e6;
          resolve({ status: res.statusCode, ms, bytes });
        });
      }
    );
    req.on('error', (err) => {
      const ms = Number(process.hrtime.bigint() - started) / 1e6;
      resolve({ status: 0, ms, bytes: 0, error: err.code || err.message });
    });
    req.setTimeout(cfg.timeoutMs, () => req.destroy(new Error('timeout')));
    if (body && cfg.method !== 'GET' && cfg.method !== 'HEAD') req.write(body);
    req.end();
  });
}

async function runPhase(durationSec, collect) {
  const until = Date.now() + durationSec * 1000;
  const results = [];

  async function worker() {
    while (Date.now() < until) {
      const r = await requestOnce();
      if (collect) results.push(r);
    }
  }

  const workers = Array.from({ length: cfg.concurrency }, () => worker());
  await Promise.all(workers);
  return results;
}

function summarize(results, wallMs) {
  const latencies = results.map((r) => r.ms).sort((a, b) => a - b);
  const ok = results.filter((r) => r.status >= 200 && r.status < 300).length;
  const errors = {};
  for (const r of results) {
    if (r.status >= 200 && r.status < 300) continue;
    const key = r.status === 0 ? `ERR:${r.error || 'unknown'}` : `HTTP:${r.status}`;
    errors[key] = (errors[key] || 0) + 1;
  }
  const total = results.length;
  const sum = latencies.reduce((a, b) => a + b, 0);
  return {
    total,
    success: ok,
    failed: total - ok,
    successRate: total ? +(ok / total).toFixed(4) : 0,
    rps: wallMs ? +(total / (wallMs / 1000)).toFixed(2) : 0,
    latencyMs: {
      min: latencies.length ? +latencies[0].toFixed(2) : 0,
      avg: total ? +(sum / total).toFixed(2) : 0,
      p50: +percentile(latencies, 50).toFixed(2),
      p90: +percentile(latencies, 90).toFixed(2),
      p95: +percentile(latencies, 95).toFixed(2),
      p99: +percentile(latencies, 99).toFixed(2),
      max: latencies.length ? +latencies[latencies.length - 1].toFixed(2) : 0,
    },
    errors,
  };
}

(async () => {
  console.log(`[bench] ${cfg.label} -> ${cfg.method} ${url.href}`);
  console.log(`[bench] concurrency=${cfg.concurrency} duration=${cfg.durationSec}s warmup=${cfg.warmupSec}s`);

  if (cfg.warmupSec > 0) {
    console.log('[bench] warmup...');
    await runPhase(cfg.warmupSec, false);
  }

  const startedAt = new Date();
  const t0 = Date.now();
  const results = await runPhase(cfg.durationSec, true);
  const wallMs = Date.now() - t0;
  const endedAt = new Date();

  const summary = {
    label: cfg.label,
    target: url.href,
    method: cfg.method,
    concurrency: cfg.concurrency,
    durationSec: cfg.durationSec,
    startedAt: startedAt.toISOString(),
    endedAt: endedAt.toISOString(),
    wallMs,
    ...summarize(results, wallMs),
  };

  const line = JSON.stringify(summary, null, 2);
  console.log(line);
  if (cfg.output) {
    fs.mkdirSync(require('path').dirname(cfg.output), { recursive: true });
    fs.writeFileSync(cfg.output, line + '\n');
    console.log(`[bench] written to ${cfg.output}`);
  }

  agent.destroy();
  if (cfg.minSuccessRate !== null && summary.successRate < cfg.minSuccessRate) {
    console.error(`[bench] successRate ${summary.successRate} < ${cfg.minSuccessRate}`);
    process.exit(1);
  }
})().catch((err) => {
  console.error('[bench] fatal:', err);
  process.exit(1);
});
