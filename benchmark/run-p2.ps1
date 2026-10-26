param(
  [ValidateSet('state', 'burst', 'cleanup', 'stages', 'metrics', 'e2e', 'priority', 'dup', 'pool-docker')]
  [string]$Scenario = 'state',
  [string]$Label = 'p2-lab',
  [int]$Concurrency = 120,
  [int]$DurationSec = 10,
  [int]$WarmupSec = 0,
  [string]$BaseUrl = 'http://localhost:8024',
  [string]$PathName = '/api/judge/submit',
  [string]$Since = '',
  [long]$Offset = 0,
  [int]$Last = 0,
  [int]$Count = 40,
  [string]$Mysql = 'D:\mysql\mysql\bin\mysql.exe',
  [string]$MysqlUser = 'root',
  [string]$MysqlPassword = $env:OJ_MYSQL_PASSWORD,
  [string]$RedisCli = 'D:\developer\env\Redis-x64-5.0.14.1\redis-cli.exe',
  [int]$RedisDb = 12,
  [string]$JudgeUrl = 'http://127.0.0.1:8012',
  [string]$Rabbit = 'http://127.0.0.1:15672',
  [string]$RabbitAuth = 'guest:guest'
)

# P2 lab: state / submit burst / cleanup / stage aggregation.
# ASCII only on purpose: Windows PowerShell 5.1 parses .ps1 as ANSI, non-ASCII breaks parsing.
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
# MYSQL_PWD avoids the "password on command line is insecure" warning that PS 5.1 turns into a terminating error.
if ($MysqlPassword) { $env:MYSQL_PWD = $MysqlPassword }

function Assert-MysqlPassword {
  if (-not $MysqlPassword) { throw 'Missing MySQL password: set OJ_MYSQL_PASSWORD or pass -MysqlPassword' }
}

function Invoke-Mysql([string]$sql) {
  Assert-MysqlPassword
  $out = & $Mysql "-u$MysqlUser" '--default-character-set=utf8mb4' -D oj -N -B -e $sql
  if ($LASTEXITCODE -ne 0) { throw "mysql exit code $LASTEXITCODE for: $sql" }
  return $out
}

function Get-QueueStates {
  $json = curl.exe -s -u $RabbitAuth "$Rabbit/api/queues/%2F"
  if (-not $json) { return @() }
  $queues = $json | ConvertFrom-Json
  return $queues | Where-Object { $_.name -like 'judge.*' } |
    Select-Object name, messages, messages_ready, messages_unacknowledged, consumers
}

function Show-State {
  Write-Host '[p2] problem_submit:'
  Invoke-Mysql "SELECT COUNT(*) AS rows_total, SUM(status=0) AS waiting, SUM(status=1) AS running, SUM(status=3) AS accepted FROM problem_submit"
  Write-Host '[p2] problem counters:'
  Invoke-Mysql "SELECT id, submit_num, accepted_num FROM problem"
  Write-Host '[p2] redis keys:'
  & $RedisCli -n $RedisDb --raw EVAL "return redis.call('DBSIZE')" 0
  Write-Host '[p2] queues:'
  Get-QueueStates | Format-Table -AutoSize
}

function Clear-Queues {
  foreach ($q in Get-QueueStates) {
    $code = curl.exe -s -o NUL -w '%{http_code}' -u $RabbitAuth -X DELETE "$Rabbit/api/queues/%2F/$($q.name)/contents"
    if ($code -ne '204') { Write-Warning "purge $($q.name) -> $code" }
  }
}

# Purge does not always take effect while consumers are attached; after the rows are gone the
# worker skips each message in ~1ms, so the queue drains on its own. Wait until it is empty
# before the next measurement, otherwise leftover messages pollute the run.
function Wait-QueuesDrained {
  param([int]$timeoutSec = 60)
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $total = 0
    # messages_ready only: unacked messages held by consumers are normal and never reach zero
    foreach ($q in Get-QueueStates) { $total += [int]$q.messages_ready }
    if ($total -eq 0) { return }
    Start-Sleep -Milliseconds 500
  }
  Write-Warning "queues still not empty after ${timeoutSec}s"
}

function Invoke-Cleanup {
  if (-not $Since) { throw 'cleanup needs -Since "yyyy-MM-dd HH:mm:ss" (burst start time)' }
  # Row delete comes first: the reconciler republishes stale WAITING rows every 30s,
  # so deleting rows before purging avoids a fresh backlog being published under us.
  Write-Host "[p2] delete submit rows (create_time >= $Since)"
  Invoke-Mysql "DELETE FROM problem_submit WHERE create_time >= '$Since'" | Out-Null
  Invoke-Mysql "UPDATE problem p SET submit_num=(SELECT COUNT(*) FROM problem_submit s WHERE s.problem_id=p.id), accepted_num=(SELECT COUNT(*) FROM problem_submit s WHERE s.problem_id=p.id AND s.status=3)" | Out-Null
  Write-Host '[p2] clear redis state'
  & $RedisCli -n $RedisDb --raw EVAL "local n=0; for _,pat in ipairs({'judge:state:*','judge:run:*','judge:age:*','judge:metrics:*'}) do local ks=redis.call('KEYS',pat); for i=1,#ks do redis.call('DEL',ks[i]); n=n+1 end end; return n" 0
  Write-Host '[p2] purge queues'
  Clear-Queues
  Start-Sleep -Seconds 1
  Clear-Queues
  Wait-QueuesDrained
  Show-State
}

function Invoke-Burst {
  $cookieFile = Join-Path $repo '.runtime/cookie.txt'
  if (-not (Test-Path $cookieFile)) { throw 'missing .runtime/cookie.txt, run benchmark/setup.ps1 first' }
  $env:COOKIE = Get-Content $cookieFile -Raw
  $env:BASE_URL = $BaseUrl
  $env:PATHNAME = $PathName
  $env:METHOD = 'POST'
  $env:CONCURRENCY = "$Concurrency"
  $env:DURATION_SEC = "$DurationSec"
  $env:WARMUP_SEC = "$WarmupSec"
  $env:LABEL = $Label
  $env:BODY_FILE = Join-Path $repo '.runtime/submit-body.json'
  $env:OUTPUT = Join-Path $repo "benchmark/results/$Label.json"
  $since = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
  Write-Host "[p2] burst label=$Label target=$BaseUrl$PathName concurrency=$Concurrency duration=${DurationSec}s since=$since"
  node (Join-Path $PSScriptRoot 'load.js')
  if ($LASTEXITCODE -ne 0) { throw "load.js exit code $LASTEXITCODE" }
  Write-Host ''
  Write-Host "[p2] cleanup: .\benchmark\run-p2.ps1 -Scenario cleanup -Since '$since'"
}

function Get-StageReport {
  param([string]$logPath, [long]$offset = 0, [int]$last = 0)
  if (-not (Test-Path $logPath)) { throw "log not found: $logPath" }
  # Offset lets one log file serve many runs: only lines appended after the offset are counted.
  $lines = @()
  if ($offset -gt 0) {
    $stream = [System.IO.File]::Open($logPath, 'Open', 'Read', 'ReadWrite')
    try {
      $stream.Seek($offset, 'Begin') | Out-Null
      $reader = New-Object System.IO.StreamReader($stream)
      while (-not $reader.EndOfStream) {
        $line = $reader.ReadLine()
        if ($line -match 'evt=(judge\.stage|submit\.stage)') { $lines += $line }
      }
    } finally { $stream.Dispose() }
  } else {
    $lines = Select-String -Path $logPath -Pattern 'evt=(judge\.stage|submit\.stage)' | ForEach-Object { $_.Line }
    if ($last -gt 0 -and $lines.Count -gt $last) {
      $lines = $lines[($lines.Count - $last)..($lines.Count - 1)]
    }
  }
  if (-not $lines -or $lines.Count -eq 0) { Write-Host '[p2] no stage log lines (instrument first)'; return }

  $rows = @()
  foreach ($line in $lines) {
    $kv = @{}
    foreach ($m in [regex]::Matches($line, '([A-Za-z][A-Za-z0-9_.]*)=([^\s]+)')) {
      $kv[$m.Groups[1].Value] = $m.Groups[2].Value
    }
    if ($kv.ContainsKey('evt')) { $rows += [pscustomobject]$kv }
  }
  foreach ($g in ($rows | Group-Object evt)) {
    Write-Host "[p2] $($g.Name): n=$($g.Count)"
    $props = $g.Group[0].PSObject.Properties.Name | Where-Object { $_ -like '*Ms' }
    foreach ($k in $props) {
      $values = @($g.Group | ForEach-Object { [double]($_.$k) } | Sort-Object)
      if ($values.Count -eq 0) { continue }
      $p50 = $values[[int][math]::Floor($values.Count * 0.5)]
      $p95 = $values[[int][math]::Min($values.Count - 1, [int][math]::Ceiling($values.Count * 0.95) - 1)]
      $avg = [math]::Round(($values | Measure-Object -Average).Average, 1)
      Write-Host ("    {0,-14} avg={1,8} p50={2,7} p95={3,7} max={4,7}" -f $k, $avg, $p50, $p95, $values[$values.Count - 1])
    }
  }
}

function Invoke-E2E {
  param([int]$count, [string]$label)
  $cookie = Get-Content (Join-Path $repo '.runtime/cookie.txt') -Raw
  # Relative path + body file: PS 5.1 mangles inline JSON quotes for curl.exe, and curl.exe
  # cannot read a non-ASCII absolute path on this machine. Keep this file ASCII-only.
  Push-Location $repo
  $ids = New-Object System.Collections.Generic.List[string]
  $submitWatch = [System.Diagnostics.Stopwatch]::StartNew()
  for ($i = 0; $i -lt $count; $i++) {
    $resp = curl.exe -s -X POST "$JudgeUrl/judge/submit" -H 'Content-Type: application/json' -H "Cookie: $cookie" --data-binary '@.runtime/submit-body.json'
    $m = [regex]::Match([string]$resp, '"id"\s*:\s*"?(\d{10,})"?')
    if ($m.Success) { $ids.Add($m.Groups[1].Value) } else { Write-Warning "submit failed: $resp" }
  }
  $submitWatch.Stop()
  if ($ids.Count -eq 0) { throw 'no submissions accepted' }

  $drainWatch = [System.Diagnostics.Stopwatch]::StartNew()
  $terminal = @{}
  $deadline = (Get-Date).AddMinutes(15)
  while ((Get-Date) -lt $deadline -and $terminal.Count -lt $ids.Count) {
    foreach ($id in $ids) {
      if ($terminal.ContainsKey($id)) { continue }
      $s = curl.exe -s "$JudgeUrl/judge/submit/$id/status" -H "Cookie: $cookie"
      if ([string]$s -match '"status":\s*([23])') { $terminal[$id] = $Matches[1] }
    }
    Start-Sleep -Milliseconds 300
  }
  $drainWatch.Stop()

  $accepted = ($terminal.Values | Where-Object { $_ -eq '3' }).Count
  $result = [ordered]@{
    label          = $label
    count          = $ids.Count
    terminal       = $terminal.Count
    accepted       = $accepted
    submitWallSec  = [math]::Round($submitWatch.Elapsed.TotalSeconds, 2)
    drainSec       = [math]::Round($drainWatch.Elapsed.TotalSeconds, 2)
    judgeTps       = [math]::Round($terminal.Count / [math]::Max(0.001, $drainWatch.Elapsed.TotalSeconds), 2)
    submitPerSec   = [math]::Round($ids.Count / [math]::Max(0.001, $submitWatch.Elapsed.TotalSeconds), 2)
  }
  $json = ($result | ConvertTo-Json)
  Write-Host $json
  $outPath = Join-Path $repo "benchmark/results/$label.json"
  Set-Content -Path $outPath -Value $json
  Pop-Location
  Write-Host "[p2] written to $outPath"
  return $result
}

function Read-LogWindow {
  param([string]$path, [long]$offset, [string]$pattern)
  # Same offset trick as Get-StageReport: only lines appended after the offset belong to this run.
  $out = New-Object System.Collections.Generic.List[string]
  $stream = [System.IO.File]::Open($path, 'Open', 'Read', 'ReadWrite')
  try {
    $stream.Seek($offset, 'Begin') | Out-Null
    $reader = New-Object System.IO.StreamReader($stream)
    while (-not $reader.EndOfStream) {
      $line = $reader.ReadLine()
      if ($line -match $pattern) { $out.Add($line) }
    }
  } finally { $stream.Dispose() }
  return $out
}

function Invoke-PriorityExperiment {
  param([int]$normalCount = 24, [int]$highCount = 6, [int]$timeoutSec = 120)
  # Goal: show HIGH tasks overtake a NORMAL backlog. Serial submits keep the backlog small on
  # purpose (a concurrent burst would queue hundreds and trigger aging, which invalidates it).
  $cookie = Get-Content (Join-Path $repo '.runtime/cookie.txt') -Raw
  $logPath = Join-Path $repo '.runtime/run/judge/judge.log'
  Push-Location $repo
  try {
    # Warm up first: the judge -> user Dubbo getSecretByUid call can exceed its 3s RPC timeout on
    # the very first invocation after a restart, which retries every task and doubles its e2e.
    $warmup = curl.exe -s -X POST "$JudgeUrl/judge/submit" -H 'Content-Type: application/json' -H "Cookie: $cookie" --data-binary '@.runtime/submit-body.json'
    $warmId = [regex]::Match([string]$warmup, '"id"\s*:\s*"?(\d{10,})"?')
    if ($warmId.Success) {
      $wid = $warmId.Groups[1].Value
      $warmDeadline = (Get-Date).AddSeconds(90)
      while ((Get-Date) -lt $warmDeadline) {
        $st = curl.exe -s "$JudgeUrl/judge/submit/$wid/status" -H "Cookie: $cookie"
        if ([string]$st -match '"status":\s*[23]') { break }
        Start-Sleep -Seconds 1
      }
      Write-Host "[p2] warmup submit $wid done"
    }
    $offset = (Get-Item $logPath).Length
    $since = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    Write-Host "[p2] priority experiment normal=$normalCount high=$highCount offset=$offset since=$since"
    $normalIds = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $normalCount; $i++) {
      $resp = curl.exe -s -X POST "$JudgeUrl/judge/submit" -H 'Content-Type: application/json' -H "Cookie: $cookie" --data-binary '@.runtime/submit-body.json'
      $m = [regex]::Match([string]$resp, '"id"\s*:\s*"?(\d{10,})"?')
      if ($m.Success) { $normalIds.Add($m.Groups[1].Value) } else { Write-Warning "normal submit failed: $resp" }
    }
    # HIGH side: rejudge endpoint marks the task HIGH (old terminal rows, no leak into the backlog)
    $candidates = Invoke-Mysql "SELECT id FROM problem_submit WHERE status=3 AND is_delete=0 AND create_time < DATE_SUB(NOW(), INTERVAL 2 MINUTE) ORDER BY id DESC LIMIT $highCount"
    $highIds = @($candidates | Where-Object { $_ -match '^\d+$' })
    foreach ($id in $highIds) {
      $resp = curl.exe -s -X POST "$JudgeUrl/judge/submit/$id/rejudge" -H "Cookie: $cookie"
      if ([string]$resp -notmatch '"code"\s*:\s*(0|200)') { Write-Warning "rejudge $id -> $resp" }
    }
    Write-Host "[p2] submitted normal=$($normalIds.Count) high=$($highIds.Count)"
    $expected = $normalIds.Count + $highIds.Count
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    $done = 0
    while ((Get-Date) -lt $deadline) {
      Start-Sleep -Seconds 2
      $done = (Read-LogWindow -path $logPath -offset $offset -pattern 'evt=judge\.task\.done').Count
      if ($done -ge $expected) { break }
    }
    Write-Host "[p2] done events=$done expected=$expected"
    $rows = @()
    foreach ($line in (Read-LogWindow -path $logPath -offset $offset -pattern 'evt=judge\.task\.done')) {
      $kv = @{}
      foreach ($m in [regex]::Matches($line, '([A-Za-z][A-Za-z0-9_.]*)=([^\s]+)')) { $kv[$m.Groups[1].Value] = $m.Groups[2].Value }
      if ($kv.ContainsKey('e2eMs')) { $rows += [pscustomobject]$kv }
    }
    foreach ($g in ($rows | Group-Object priority)) {
      $v = @($g.Group | ForEach-Object { [double]$_.e2eMs } | Sort-Object)
      $p50 = $v[[int][math]::Floor($v.Count * 0.5)]
      $p95 = $v[[int][math]::Min($v.Count - 1, [int][math]::Ceiling($v.Count * 0.95) - 1)]
      Write-Host ("[p2] priority={0} n={1} p50={2}ms p95={3}ms max={4}ms" -f $g.Name, $v.Count, [math]::Round($p50), [math]::Round($p95), [math]::Round($v[$v.Count - 1]))
    }
    $promoted = (Read-LogWindow -path $logPath -offset $offset -pattern 'evt=judge\.aging ').Count
    Write-Host "[p2] aging promotions in window: $promoted"
    Write-Host "[p2] cleanup: .\benchmark\run-p2.ps1 -Scenario cleanup -Since '$since'"
  } finally { Pop-Location }
}

function Invoke-DuplicateDelivery {
  param([int]$copies = 8, [int]$timeoutSec = 60)
  # P1 regression: the same submitId delivered many times must be judged exactly once.
  # Publishes extra copies straight to judge.exchange (as a redelivery/broker duplicate would).
  $cookie = Get-Content (Join-Path $repo '.runtime/cookie.txt') -Raw
  $logPath = Join-Path $repo '.runtime/run/judge/judge.log'
  $offset = (Get-Item $logPath).Length
  Push-Location $repo
  try {
    $resp = curl.exe -s -X POST "$JudgeUrl/judge/submit" -H 'Content-Type: application/json' -H "Cookie: $cookie" --data-binary '@.runtime/submit-body.json'
    $m = [regex]::Match([string]$resp, '"id"\s*:\s*"?(\d{10,})"?')
    if (-not $m.Success) { throw "submit failed: $resp" }
    $id = $m.Groups[1].Value
    $row = Invoke-Mysql "SELECT problem_id, user_id, language FROM problem_submit WHERE id=$id"
    $parts = ($row -split "`t")
    $payload = [ordered]@{
      schemaVersion = 1
      msgId         = [guid]::NewGuid().ToString()
      submitId      = [long]$id
      problemId     = [long]$parts[0]
      uid           = [long]$parts[1]
      language      = $parts[2]
      attempt       = 1
      priority      = 'NORMAL'
      enqueuedAt    = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
      traceId       = ([guid]::NewGuid().ToString('N')).Substring(0, 32)
      code          = $null
    } | ConvertTo-Json -Compress
    # Force the body through a file: PS 5.1 mangles inline JSON quotes for curl.exe.
    $pubPath = Join-Path $repo '.runtime/publish-body.json'
    for ($i = 0; $i -lt $copies; $i++) {
      $body = [ordered]@{
        properties     = @{ content_type = 'application/json'; delivery_mode = 2 }
        routing_key    = 'judge.task.normal'
        payload        = $payload
        payload_encoding = 'string'
      } | ConvertTo-Json -Compress
      Set-Content -Path $pubPath -Value $body -Encoding ASCII
      $out = curl.exe -s -u $RabbitAuth -H 'Content-Type: application/json' --data-binary "@$pubPath" "$Rabbit/api/exchanges/%2F/judge.exchange/publish"
      if ([string]$out -notmatch '"routed"\s*:\s*true') { Write-Warning "publish $i -> $out" }
    }
    Write-Host "[p2] submitId=$id published $copies extra copies"
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
      Start-Sleep -Seconds 3
      $done = @(Read-LogWindow -path $logPath -offset $offset -pattern "evt=judge\.task\.done submitId=$id ").Count
      $skip = @(Read-LogWindow -path $logPath -offset $offset -pattern "evt=judge\.task\.skip submitId=$id ").Count
      if ($done -ge 1 -and ($done + $skip) -ge ($copies + 1)) { break }
    }
    $doneAll = @(Read-LogWindow -path $logPath -offset $offset -pattern 'evt=judge\.task\.done')
    $mine = @($doneAll | Where-Object { $_ -match "submitId=$id " })
    $skips = @(Read-LogWindow -path $logPath -offset $offset -pattern 'evt=judge\.task\.skip')
    $db = Invoke-Mysql "SELECT status, attempt, version FROM problem_submit WHERE id=$id"
    Write-Host "[p2] submitId=$id judge.done=$($mine.Count) (expect 1) skips=$($skips.Count) db(status,attempt,version)=$db"
    # cluster hash tags: the Redis key is judge:state:{submitId}
    $redis = & $RedisCli -n $RedisDb --raw HGET "judge:state:{$id}" version
    $redisStatus = & $RedisCli -n $RedisDb --raw HGET "judge:state:{$id}" status
    Write-Host "[p2] redis status=$redisStatus version=$redis"
  } finally { Pop-Location }
}

function Show-Metrics {  $cookieFile = Join-Path $repo '.runtime/cookie.txt'
  $cookie = if (Test-Path $cookieFile) { Get-Content $cookieFile -Raw } else { '' }
  Write-Host '[p2] judge metrics:'
  curl.exe -s "$JudgeUrl/judge/metrics" -H "Cookie: $cookie"
  Write-Host ''
  Write-Host '[p2] sandbox metrics:'
  curl.exe -s 'http://127.0.0.1:8013/sandbox/metrics'
  Write-Host ''
}

function Get-SandboxMetrics {
  $raw = curl.exe -s 'http://127.0.0.1:8013/sandbox/metrics'
  if (-not $raw) { throw 'sandbox metrics is empty; is the sandbox still starting?' }
  return ($raw | ConvertFrom-Json)
}

function Wait-Sandbox {
  param([int]$timeoutSec = 150)
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $code = curl.exe -s -o NUL -w '%{http_code}' 'http://127.0.0.1:8013/sandbox/metrics'
    if ($code -eq '200') { return }
    Start-Sleep -Seconds 1
  }
  throw "sandbox did not answer on 8013 within ${timeoutSec}s (see .runtime/run/sandbox/sandbox.log)"
}

function Stop-Sandbox {
  # Kill -9 on purpose: matches a crash, and the next start must still work.
  param([switch]$KeepContainers)
  $conns = Get-NetTCPConnection -LocalPort 8013 -State Listen -ErrorAction SilentlyContinue
  foreach ($sandboxPid in ($conns.OwningProcess | Sort-Object -Unique)) {
    Stop-Process -Id $sandboxPid -Force
    Write-Host "[p2] sandbox pid $sandboxPid stopped"
  }
  $deadline = (Get-Date).AddSeconds(30)
  while ((Get-Date) -lt $deadline) {
    if (-not (Get-NetTCPConnection -LocalPort 8013 -State Listen -ErrorAction SilentlyContinue)) { break }
    Start-Sleep -Milliseconds 300
  }
  if (Get-NetTCPConnection -LocalPort 8013 -State Listen -ErrorAction SilentlyContinue) {
    throw 'port 8013 still listening after kill'
  }
  if (-not $KeepContainers) {
    # A killed process leaves its containers behind. Remove them so every group starts from the
    # same container set; otherwise the live-container column mixes two runs.
    $stale = @(docker ps -aq --filter 'name=pool-') + @(docker ps -aq --filter 'name=ephemeral-')
    foreach ($id in $stale) { docker rm -f $id | Out-Null }
    if ($stale.Count -gt 0) { Write-Host "[p2] removed $($stale.Count) stale sandbox container(s)" }
  }
}

function Start-Sandbox {
  param([bool]$poolEnabled)
  $flag = if ($poolEnabled) { '--oj.sandbox.pool.enabled=true' } else { '--oj.sandbox.pool.enabled=false' }
  $dir = Join-Path $repo '.runtime/run/sandbox'
  $bat = Join-Path $dir 'start.bat'
  Write-Host "[p2] start sandbox $flag"
  # WMI Create: detached process with no inherited handles. Start-Process (and cmd /c) keep the
  # child attached to the caller's stdout, so the calling shell blocks until the sandbox exits;
  # "cmd /c start.bat" also fails to resolve the name when the cwd has non-ASCII characters.
  $spawn = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
    CommandLine = "cmd.exe /c `"`"$bat`" $flag`""
  }
  if ($spawn.ReturnValue -ne 0) { throw "failed to start sandbox: WMI return $($spawn.ReturnValue)" }
  Wait-Sandbox
}

function Get-StageDelta {
  param($before, $after, [string]$stage)
  $b = $before.stages.PSObject.Properties[$stage].Value
  $a = $after.stages.PSObject.Properties[$stage].Value
  $bCount = if ($b) { [long]$b.count } else { 0 }
  $bSum = if ($b) { [long]$b.sumMs } else { 0 }
  $aCount = if ($a) { [long]$a.count } else { 0 }
  $aSum = if ($a) { [long]$a.sumMs } else { 0 }
  $count = $aCount - $bCount
  $sum = $aSum - $bSum
  return [ordered]@{
    count = $count
    sumMs = $sum
    avgMs = if ($count -gt 0) { [math]::Round($sum / $count, 1) } else { 0 }
  }
}

function Measure-PoolRound {
  param([string]$group, [int]$round, [int]$count)
  $before = Get-SandboxMetrics
  $result = Invoke-E2E -count $count -label "$group-r$round"
  $after = Get-SandboxMetrics
  $stages = [ordered]@{}
  foreach ($name in @('acquire', 'compile', 'run', 'cleanup', 'total')) {
    $stages[$name] = Get-StageDelta -before $before -after $after -stage $name
  }
  $containerIds = @()
  if ($after.pool.slots) { $containerIds = @($after.pool.slots | ForEach-Object { $_.containerId }) }
  return [ordered]@{
    group                 = $group
    round                 = $round
    count                 = $result.count
    terminal              = $result.terminal
    accepted              = $result.accepted
    drainSec              = $result.drainSec
    judgePerSec           = $result.judgeTps
    containersCreated     = [long]$after.pool.created - [long]$before.pool.created
    containersReused      = [long]$after.pool.reused - [long]$before.pool.reused
    containersDiscarded   = [long]$after.pool.discarded - [long]$before.pool.discarded
    livePoolContainers    = @(docker ps --filter 'name=pool-' --format '{{.ID}}').Count
    containerIds          = $containerIds
    stages                = $stages
  }
}

function Invoke-PoolDockerAb {
  param([int]$count = 40, [int]$rounds = 2, [string]$jdkImage = 'jenkins/agent:latest-jdk17')
  # Goal: prove the pool reuses containers and show the delta, NOT an absolute throughput
  # number: the image is JDK 17 while every earlier native number was JDK 8.
  $since = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
  $javaVersion = (docker run --rm $jdkImage sh -c 'java -version 2>&1' | Select-Object -First 1)
  Write-Host "[p2] image=$jdkImage ($javaVersion)"
  Write-Host "[p2] pool A/B: count=$count rounds=$rounds (only the LAST round of each group counts)"
  Write-Host '[p2] WARNING JDK 17 image != native JDK 8: compare pool-off vs pool-on only'
  $rows = New-Object System.Collections.Generic.List[object]
  foreach ($group in @(@{ name = 'pool-off'; enabled = $false }, @{ name = 'pool-on'; enabled = $true })) {
    Stop-Sandbox
    Start-Sandbox -poolEnabled $group.enabled
    for ($r = 1; $r -le $rounds; $r++) {
      $row = Measure-PoolRound -group $group.name -round $r -count $count
      $rows.Add($row)
      Write-Host ("[p2] {0} r{1}: judge/s={2} drain={3}s created={4} reused={5} total.avg={6}ms cleanup.avg={7}ms live={8}" -f `
          $row.group, $row.round, $row.judgePerSec, $row.drainSec, $row.containersCreated,
        $row.containersReused, $row.stages.total.avgMs, $row.stages.cleanup.avgMs, $row.livePoolContainers)
    }
  }
  # Leave the lab in the default state (pool on). Keep the old containers on purpose: this
  # restart is the regression test for "kill -9 leaves pool-N behind, next start must recover".
  $sandboxLog = Join-Path $repo '.runtime/run/sandbox/sandbox.log'
  $logOffset = (Get-Item $sandboxLog).Length
  Write-Host '[p2] restore with stale containers kept (tests create-time name-conflict recovery)'
  Stop-Sandbox -KeepContainers
  Start-Sandbox -poolEnabled $true
  $final = Get-SandboxMetrics
  Write-Host ("[p2] sandbox restored: backend={0} mode={1} containers={2}" -f $final.pool.backend, $final.pool.mode,
    @(docker ps --filter 'name=pool-' --format '{{.ID}}').Count)
  $staleRemoved = @(Read-LogWindow -path $sandboxLog -offset $logOffset -pattern 'evt=sandbox\.slot\.stale_removed')
  Write-Host "[p2] stale_removed lines in this restart: $($staleRemoved.Count) (expect 8)"
  $staleRemoved = @(Select-String -Path (Join-Path $repo '.runtime/run/sandbox/sandbox.log') -Pattern 'evt=sandbox\.slot\.stale_removed' | Select-Object -Last 8)
  Write-Host "[p2] stale_removed lines in sandbox log: $($staleRemoved.Count) (expect 8)"

  $out = [ordered]@{
    label       = 'pool-docker'
    generatedAt = (Get-Date).ToString('s')
    image       = "$jdkImage ($javaVersion)"
    count       = $count
    rounds      = $rows
  }
  $outPath = Join-Path $repo 'benchmark/results/pool-docker-ab.json'
  Set-Content -Path $outPath -Value ($out | ConvertTo-Json -Depth 6)
  Write-Host "[p2] written to $outPath"
  Write-Host "[p2] cleanup: .\benchmark\run-p2.ps1 -Scenario cleanup -Since '$since'"
}

switch ($Scenario) {
  'state' { Show-State }
  'burst' { Invoke-Burst }
  'cleanup' { Invoke-Cleanup }
  'stages' {
    $logPath = Join-Path $repo '.runtime/run/judge/judge.log'
    Get-StageReport -logPath $logPath -offset $Offset -last $Last
  }
  'metrics' { Show-Metrics }
  'e2e' { Invoke-E2E -count $Count -label $Label }
  'priority' { Invoke-PriorityExperiment -normalCount $Count }
  'dup' { Invoke-DuplicateDelivery -copies $Count }
  'pool-docker' { Invoke-PoolDockerAb -count $Count }
}
