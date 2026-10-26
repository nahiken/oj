param(
  [string]$Gateway = 'http://localhost:8024',
  [int]$SteadySeconds = 300,
  [int]$BurstSeconds = 15,
  [string]$OutputRoot = 'benchmark/results',
  [switch]$Quick
)

$ErrorActionPreference = 'Stop'
if ($Quick) {
  $SteadySeconds = 30
  $BurstSeconds = 15
}

$repo = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $repo '.runtime'
$cookieFile = Join-Path $runtime 'cookie.txt'
if (-not (Test-Path $cookieFile)) { throw '缺少 .runtime/cookie.txt，请先运行 benchmark/setup.ps1' }

$cookie = (Get-Content $cookieFile -Raw).Trim()
$submitBody = Join-Path $runtime 'submit-body.json'
$queryBody = Join-Path $runtime 'query-body.json'
$runBody = Join-Path $runtime 'run-body.json'
foreach ($f in @($submitBody, $queryBody, $runBody)) {
  if (-not (Test-Path $f)) { throw "缺少 $f，请先运行 benchmark/setup.ps1" }
}

$outDir = Join-Path $repo (Join-Path $OutputRoot (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$env:BASE_URL = $Gateway
$env:COOKIE = $cookie
$env:TIMEOUT_MS = '60000'

$scenarios = @(
  @{ Label = 'steady-20';  Concurrency = 20;  Duration = $SteadySeconds; Warmup = 10; Pathname = '/api/judge/submit';     Body = $submitBody },
  @{ Label = 'steady-50';  Concurrency = 50;  Duration = $SteadySeconds; Warmup = 10; Pathname = '/api/judge/submit';     Body = $submitBody },
  @{ Label = 'steady-100'; Concurrency = 100; Duration = $SteadySeconds; Warmup = 10; Pathname = '/api/judge/submit';     Body = $submitBody },
  @{ Label = 'burst-120-15s'; Concurrency = 120; Duration = $BurstSeconds; Warmup = 0; Pathname = '/api/judge/submit';    Body = $submitBody },
  @{ Label = 'query-20';   Concurrency = 20;  Duration = [Math]::Max(15, [int]($SteadySeconds / 4)); Warmup = 5; Pathname = '/api/judge/submit/page/vo'; Body = $queryBody },
  @{ Label = 'run-20';     Concurrency = 20;  Duration = [Math]::Max(15, [int]($SteadySeconds / 4)); Warmup = 5; Pathname = '/api/judge/run'; Body = $runBody }
)

$env:COOKIE = $cookie

foreach ($s in $scenarios) {
  Write-Host "`n=== $($s.Label) ==="
  $env:PATHNAME = $s.Pathname
  $env:BODY_FILE = $s.Body
  $env:CONCURRENCY = [string]$s.Concurrency
  $env:DURATION_SEC = [string]$s.Duration
  $env:WARMUP_SEC = [string]$s.Warmup
  $env:LABEL = $s.Label
  $env:OUTPUT = Join-Path $outDir "$($s.Label).json"
  & node (Join-Path $PSScriptRoot 'load.js')
  if ($LASTEXITCODE -ne 0) { Write-Warning "场景 $($s.Label) 退出码 $LASTEXITCODE" }
}

Write-Host "`n[baseline] results in $outDir"
$rows = Get-ChildItem $outDir -Filter '*.json' | ForEach-Object {
  $j = Get-Content $_.FullName -Raw | ConvertFrom-Json
  [pscustomobject]@{
    label       = $j.label
    concurrency = $j.concurrency
    total       = $j.total
    successRate = $j.successRate
    rps         = $j.rps
    p50         = $j.latencyMs.p50
    p95         = $j.latencyMs.p95
    p99         = $j.latencyMs.p99
    max         = $j.latencyMs.max
  }
}
$rows | Format-Table -AutoSize
$rows | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $outDir 'summary.json')
$rows | ForEach-Object {
  "| $($_.label) | $($_.concurrency) | $($_.total) | $($_.successRate) | $($_.rps) | $($_.p50) | $($_.p95) | $($_.p99) | $($_.max) |"
} | Set-Content (Join-Path $outDir 'summary.md')
Write-Host "[baseline] wrote summary.json and summary.md"
