param(
  [int]$E2eCount = 10,
  [int]$Concurrency = 120,
  [int]$DurationSec = 15,
  [string]$Gateway = 'http://localhost:8024'
)

# P1 验证脚本：端到端延迟 + 120 并发突发
# 前置：setup.ps1 已执行（Cookie/题目就绪），judge 以 async 模式启动
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$cursor = Get-Date -Format 'yyyyMMdd-HHmmss'
$outDir = Join-Path $repo "benchmark/results/p1-$cursor"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$cookieFile = Join-Path $repo '.runtime/cookie.txt'
if (-not (Test-Path $cookieFile)) {
  throw '缺少 .runtime/cookie.txt，先运行 benchmark/setup.ps1'
}
$env:COOKIE = Get-Content $cookieFile -Raw

Write-Host "[p1] gateway health: $Gateway"
try {
  Invoke-WebRequest -UseBasicParsing -Uri "$Gateway/api/judge/submit/summary" -TimeoutSec 5 | Out-Null
} catch {
  Write-Host '[p1] summary 接口未就绪（可能需要登录态），继续执行'
}

Write-Host "[p1] e2e: n=$E2eCount"
$env:N = "$E2eCount"
$env:SUBMIT_CONCURRENCY = '4'
$env:LABEL = 'p1-e2e'
$env:BASE_URL = $Gateway
$env:OUTPUT = Join-Path $outDir 'e2e.json'
node (Join-Path $PSScriptRoot 'e2e.js')

Write-Host "[p1] burst: concurrency=$Concurrency duration=${DurationSec}s"
$env:CONCURRENCY = "$Concurrency"
$env:DURATION_SEC = "$DurationSec"
$env:WARMUP_SEC = '0'
$env:LABEL = 'p1-burst'
$env:BODY_FILE = Join-Path $repo '.runtime/submit-body.json'
$env:OUTPUT = Join-Path $outDir 'burst.json'
node (Join-Path $PSScriptRoot 'load.js')

Write-Host "[p1] results written to $outDir"
Write-Host '[p1] 注意：突发会在队列留下积压，验证完记得清理 judge.queue.* 与本次产生的 problem_submit 行'
