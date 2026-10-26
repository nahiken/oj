param(
  [ValidateSet('state', 'fence', 'dup', 'multideliver', 'recover', 'metrics', 'e2e', 'cleanup')]
  [string]$Scenario = 'state',
  [string]$Label = 'p3-lab',
  [int]$CloseAfterMs = 4000,
  [int]$Runs = 10,
  [int]$Count = 10,
  [int]$Copies = 1,
  [string]$Since = '',
  [long]$ProblemId = 0,
  [string]$SubmitBody = '.runtime/submit-body.json',
  [string]$Mysql = 'D:\mysql\mysql\bin\mysql.exe',
  [string]$MysqlUser = 'root',
  [string]$MysqlPassword = $env:OJ_MYSQL_PASSWORD,
  [string]$RedisCli = 'D:\developer\env\Redis-x64-5.0.14.1\redis-cli.exe',
  [int]$RedisDb = 12,
  [string]$JudgeUrl = 'http://127.0.0.1:8012',
  [string]$JudgeBUrl = 'http://127.0.0.1:8022',
  [string]$Rabbit = 'http://127.0.0.1:15672',
  [string]$RabbitAuth = 'guest:guest'
)

# P3 fault injection: lease/fencing contract checks, AMQP partition, kill -9 recovery.
# ASCII only on purpose: Windows PowerShell 5.1 parses .ps1 as ANSI, non-ASCII breaks parsing.
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
# Two instances => two log files: cmd's ">>" does not share a log between appenders.
$logFiles = @(
  (Join-Path $repo '.runtime/run/judge/judge.log'),
  (Join-Path $repo '.runtime/run/judge/judge-b.log')
)
$script:fail = 0
if ($MysqlPassword) { $env:MYSQL_PWD = $MysqlPassword }

# ---------------- helpers ----------------

function Assert-MysqlPassword {
  if (-not $MysqlPassword) { throw 'Missing MySQL password: set OJ_MYSQL_PASSWORD or pass -MysqlPassword' }
}

function Invoke-Mysql([string]$sql) {
  Assert-MysqlPassword
  $out = & $Mysql --host=127.0.0.1 "--user=$MysqlUser" '--default-character-set=utf8mb4' -D oj -N -B -e $sql
  if ($LASTEXITCODE -ne 0) { throw "mysql exit code $LASTEXITCODE for: $sql" }
  return $out
}

function Invoke-Redis([string[]]$redisArgs) {
  return (& $RedisCli -n $RedisDb --raw @redisArgs) -join "`n"
}

function Invoke-Lua([string]$file, [string[]]$keys, [string[]]$luaArgs) {
  $params = @('-n', "$RedisDb", '--raw', '--eval', $file) + $keys + @(',') + $luaArgs
  return ((& $RedisCli @params) -join "`n").Trim()
}

function Get-Cookie { return (Get-Content (Join-Path $repo '.runtime/cookie.txt') -Raw) }

function Get-LogOffsets {
  $offsets = @{}
  foreach ($file in $logFiles) {
    if (Test-Path $file) { $offsets[$file] = (Get-Item $file).Length } else { $offsets[$file] = 0 }
  }
  return $offsets
}

function Get-LogLines($offsets, [string]$pattern) {
  $out = New-Object System.Collections.Generic.List[string]
  foreach ($file in $logFiles) {
    if (-not (Test-Path $file)) { continue }
    $stream = [System.IO.File]::Open($file, 'Open', 'Read', 'ReadWrite')
    try {
      $stream.Seek([long]$offsets[$file], 'Begin') | Out-Null
      $reader = New-Object System.IO.StreamReader($stream)
      while (-not $reader.EndOfStream) {
        $line = $reader.ReadLine()
        if ($line -match $pattern) { $out.Add($line) }
      }
    } finally { $stream.Dispose() }
  }
  return $out
}

function Get-LineTime([string]$line) {
  $m = [regex]::Match($line, '^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})')
  if ($m.Success) { return [datetime]::ParseExact($m.Groups[1].Value, 'yyyy-MM-dd HH:mm:ss.fff', $null) }
  return $null
}

function Get-Kv([string]$line) {
  $kv = @{}
  foreach ($m in [regex]::Matches($line, '([A-Za-z][A-Za-z0-9_.]*)=([^\s]+)')) {
    $kv[$m.Groups[1].Value] = $m.Groups[2].Value
  }
  return $kv
}

function Assert-Equal([string]$name, $expected, $actual) {
  if ("$expected" -eq "$actual") {
    Write-Host ("[p3] PASS {0} = {1}" -f $name, $actual)
  } else {
    Write-Host ("[p3] FAIL {0} expected={1} actual={2}" -f $name, $expected, $actual)
    $script:fail++
  }
}

function Get-QueueStates {
  $json = curl.exe -s -u $RabbitAuth "$Rabbit/api/queues/%2F"
  if (-not $json) { return @() }
  return ($json | ConvertFrom-Json) | Where-Object { $_.name -like 'judge.*' } |
    Select-Object name, messages_ready, consumers
}

function Clear-Queues {
  foreach ($q in Get-QueueStates) {
    $code = curl.exe -s -o NUL -w '%{http_code}' -u $RabbitAuth -X DELETE "$Rabbit/api/queues/%2F/$($q.name)/contents"
    if ($code -ne '204') { Write-Warning "purge $($q.name) -> $code" }
  }
}

function Wait-QueuesDrained([int]$timeoutSec = 60) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $total = 0
    foreach ($q in Get-QueueStates) { $total += [int]$q.messages_ready }
    if ($total -eq 0) { return }
    Start-Sleep -Milliseconds 500
  }
  Write-Warning "queues still not empty after ${timeoutSec}s"
}

function Submit-Task {
  $cookie = Get-Cookie
  Push-Location $repo
  try {
    $resp = curl.exe -s -X POST "$JudgeUrl/judge/submit" -H 'Content-Type: application/json' -H "Cookie: $cookie" --data-binary "@$SubmitBody"
  } finally { Pop-Location }
  $m = [regex]::Match([string]$resp, '"id"\s*:\s*"?(\d{10,})"?')
  if (-not $m.Success) { throw "submit failed: $resp" }
  return $m.Groups[1].Value
}

function Wait-Terminal([string]$id, [int]$timeoutSec = 180, [string[]]$urls = @($JudgeUrl)) {
  # Try every live instance: during a kill -9 run the killed one no longer answers.
  $cookie = Get-Cookie
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    foreach ($url in $urls) {
      $s = curl.exe -s "$url/judge/submit/$id/status" -H "Cookie: $cookie"
      if ([string]$s -match '"status":\s*([23])') { return $Matches[1] }
    }
    Start-Sleep -Milliseconds 300
  }
  return $null
}

function Wait-Claim([string]$id, $offsets, [int]$timeoutSec = 60) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $lines = @(Get-LogLines $offsets "evt=judge\.task submitId=$id ")
    if ($lines.Count -gt 0) { return $lines[0] }
    Start-Sleep -Milliseconds 200
  }
  return $null
}

function Get-Metrics([string]$url) {
  $cookie = Get-Cookie
  $json = curl.exe -s "$url/judge/metrics" -H "Cookie: $cookie"
  try { return ($json | ConvertFrom-Json).data } catch { return $null }
}

function Get-InstancePids {
  # Cache pid -> instance once: Get-NetTCPConnection is unavailable/slow in this sandbox and
  # must not sit between "claim observed" and "kill", or a fast judge finishes before the kill.
  $map = @{}
  foreach ($line in (& netstat -ano | Select-String -Pattern 'LISTENING')) {
    if ("$line" -match '^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)') {
      $port = [int]$Matches[1]
      $procId = [int]$Matches[2]
      if ($port -eq 8012) { $map[$procId] = 'A' }
      elseif ($port -eq 8022) { $map[$procId] = 'B' }
    }
  }
  return $map
}

function Get-InstanceOfPid([int]$procId) {
  foreach ($line in (& netstat -ano | Select-String -Pattern 'LISTENING')) {
    if ("$line" -match '^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)') {
      $port = [int]$Matches[1]
      if ([int]$Matches[2] -eq $procId -and ($port -eq 8012 -or $port -eq 8022)) {
        return $(if ($port -eq 8012) { 'A' } else { 'B' })
      }
    }
  }
  return $null
}

function Get-StartArgs([string]$instance) {
  if ($instance -eq 'A') { return '' }
  return '--server.port=8022 --dubbo.protocol.port=8033 --oj.judge.async.aging-enabled=false --oj.judge.async.reconcile-delay-ms=3600000'
}

function Start-Instance([string]$instance) {
  $batName = if ($instance -eq 'A') { 'start.bat' } else { 'start-b.bat' }
  $bat = Join-Path $repo ".runtime\run\judge\$batName"
  # Quote the bat path once and pass the rest as one command string: this is the form proven
  # by the recover scenario (an unquoted/array form can leave cmd idle on a hidden prompt).
  $cmdLine = "`"$bat`" $(Get-StartArgs $instance)"
  Write-Host "[p3] starting instance $instance"
  Start-Process -FilePath $env:ComSpec -ArgumentList '/c', $cmdLine -WindowStyle Hidden
}

function Wait-Health([string]$url, [int]$timeoutSec = 300) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $code = curl.exe -s -o NUL -w '%{http_code}' "$url/judge/metrics"
    if ($code -eq '200') { return $true }
    Start-Sleep -Seconds 2
  }
  return $false
}

function Close-InstanceConnections([int]$procId) {
  # Spring Boot 2.7 has no spring.rabbitmq.connection-name, so identify the instance's AMQP
  # connections by the local TCP ports the process owns (management API exposes peer_port).
  # netstat -ano is used instead of Get-NetTCPConnection: the latter costs seconds on Windows.
  $ports = @()
  foreach ($line in (& netstat -ano | Select-String -Pattern 'ESTABLISHED')) {
    if ("$line" -match '^\s*TCP\s+\S+:(\d+)\s+\S+:5672\s+ESTABLISHED\s+(\d+)') {
      if ([int]$Matches[2] -eq $procId) { $ports += [int]$Matches[1] }
    }
  }
  $json = curl.exe -s -u $RabbitAuth "$Rabbit/api/connections"
  if (-not $json) { return [pscustomobject]@{ matched = 0; closed = 0; ports = $ports } }
  $targets = @(($json | ConvertFrom-Json) | Where-Object { $ports -contains [int]$_.peer_port })
  $closed = 0
  foreach ($c in $targets) {
    $enc = [uri]::EscapeDataString($c.name)
    $code = curl.exe -s -o NUL -w '%{http_code}' -u $RabbitAuth -X DELETE "$Rabbit/api/connections/$enc"
    if ($code -eq '204') { $closed++ }
  }
  return [pscustomobject]@{ matched = $targets.Count; closed = $closed; ports = $ports }
}

function Write-Result([string]$label, $payload) {
  $json = $payload | ConvertTo-Json -Depth 5
  $outPath = Join-Path $repo "benchmark/results/$label.json"
  Set-Content -Path $outPath -Value $json -Encoding ASCII
  Write-Host "[p3] written to $outPath"
}

# ---------------- scenarios ----------------

function Show-State {
  Write-Host '[p3] problem_submit:'
  Invoke-Mysql "SELECT COUNT(*) AS rows_total, SUM(status=0) AS waiting, SUM(status=1) AS running, SUM(status=3) AS accepted FROM problem_submit"
  Write-Host '[p3] queues:'
  Get-QueueStates | Format-Table -AutoSize
  $cookie = Get-Cookie
  Write-Host '[p3] judge A metrics lease:'
  curl.exe -s "$JudgeUrl/judge/metrics" -H "Cookie: $cookie"
  Write-Host ''
}

function Invoke-Fence {
  # Contract assertions against the real Lua sources (copied to an ASCII temp path so
  # redis-cli never has to open a path with non-ASCII characters).
  $luaDir = Join-Path $repo 'oj-backend-master\judge\src\main\resources\lua'
  $tmp = Join-Path $env:TEMP 'p3-lua'
  New-Item -ItemType Directory -Force -Path $tmp | Out-Null
  Copy-Item (Join-Path $luaDir 'judge_transition.lua') (Join-Path $tmp 'transition.lua') -Force
  Copy-Item (Join-Path $luaDir 'judge_lease_renew.lua') (Join-Path $tmp 'renew.lua') -Force
  Copy-Item (Join-Path $luaDir 'judge_unlock.lua') (Join-Path $tmp 'unlock.lua') -Force

  $id = 990000000000000001
  $state = "judge:state:{$id}"
  $lock = "judge:lock:{$id}"
  $epochKey = "judge:epoch:{$id}"
  Invoke-Redis @('DEL', $state, $lock, $epochKey) | Out-Null
  Invoke-Redis @('HSET', $state, 'status', '1', 'version', '5', 'epoch', '1', 'uid', '1',
    'problemId', '1', 'attempt', '1', 'ji', '{}', 'leaseUntil', '0') | Out-Null
  Invoke-Redis @('EXPIRE', $state, '600') | Out-Null
  $now = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
  $ttl = '600'

  Write-Host '[p3] fencing: stale epoch must not write a terminal state'
  # redis-cli drops empty arguments on Windows, so these asserts pass harmless non-empty
  # placeholders for the slots whose meaning is "do not write". Here ARGV[11] (newEpoch) is
  # intentionally omitted: the script must tolerate a missing tail argument.
  $stale = Invoke-Lua (Join-Path $tmp 'transition.lua') @($state) @('1', '-1', '3', 'p3-probe', '1', "$now", $ttl, '{}', '0', '0')
  Assert-Equal 'stale_epoch_result' 3 $stale
  Assert-Equal 'status_after_fence' 1 (Invoke-Redis @('HGET', $state, 'status'))
  Assert-Equal 'version_after_fence' 5 (Invoke-Redis @('HGET', $state, 'version'))

  Write-Host '[p3] fencing: stale RUNNING snapshot cannot reset a live claim (P9)'
  # Mirrors JudgeWorker.handleRunningState after the P9 fix: an expired snapshot resets with
  # its old epoch. The live generation is 1, the snapshot is 0, so Lua must return 3 and leave
  # both status and version untouched.
  $staleReset = Invoke-Lua (Join-Path $tmp 'transition.lua') @($state) @('1', '-1', '0', 'p3-probe', '2', "$now", $ttl, '{}', '0', '0')
  Assert-Equal 'stale_running_reset' 3 $staleReset
  Assert-Equal 'status_after_stale_reset' 1 (Invoke-Redis @('HGET', $state, 'status'))
  Assert-Equal 'version_after_stale_reset' 5 (Invoke-Redis @('HGET', $state, 'version'))

  Write-Host '[p3] fencing: current epoch can write and advances epoch (P12: pass all 11 args)'
  # ARGV[10]=expectedEpoch=1 (current generation), ARGV[11]=newEpoch=2. Asserting the write
  # really sets epoch=2 (before the fix no newEpoch was passed and the assertion was vacuous).
  $fresh = Invoke-Lua (Join-Path $tmp 'transition.lua') @($state) @('1', '-1', '3', 'p3-probe', '1', "$now", $ttl, '{}', '0', '1', '2')
  Assert-Equal 'current_epoch_result' 0 $fresh
  Assert-Equal 'status_after_write' 3 (Invoke-Redis @('HGET', $state, 'status'))
  Assert-Equal 'version_after_write' 6 (Invoke-Redis @('HGET', $state, 'version'))
  Assert-Equal 'epoch_after_write' 2 (Invoke-Redis @('HGET', $state, 'epoch'))

  Write-Host '[p3] fencing: the previous generation is fenced right after newEpoch is written'
  # expectedStatus=-1 so the call reaches the epoch check instead of stopping at status=RUNNING.
  # The old generation (epoch=1) must get 3 while the new one (2) owns the row.
  $old = Invoke-Lua (Join-Path $tmp 'transition.lua') @($state) @('-1', '-1', '2', 'p3-probe', '1', "$now", $ttl, '{}', '0', '1')
  Assert-Equal 'old_epoch_after_new_epoch' 3 $old
  Assert-Equal 'status_after_old_epoch' 3 (Invoke-Redis @('HGET', $state, 'status'))
  Assert-Equal 'version_after_old_epoch' 6 (Invoke-Redis @('HGET', $state, 'version'))

  Write-Host '[p3] lock: wrong token must not delete, right token must'
  Invoke-Redis @('SET', $lock, 'judge-a:777:aaaa#11', 'PX', '30000') | Out-Null
  Assert-Equal 'unlock_wrong_token' 0 (Invoke-Lua (Join-Path $tmp 'unlock.lua') @($lock) @('judge-a:777:aaaa#12'))
  Assert-Equal 'lock_exists_after_wrong_unlock' 1 (Invoke-Redis @('EXISTS', $lock))
  Assert-Equal 'unlock_right_token' 1 (Invoke-Lua (Join-Path $tmp 'unlock.lua') @($lock) @('judge-a:777:aaaa#11'))
  Assert-Equal 'lock_gone_after_right_unlock' 0 (Invoke-Redis @('EXISTS', $lock))

  Write-Host '[p3] lease renew: only the holder renews; expiry loses the lease'
  Invoke-Redis @('SET', $lock, 'judge-a:777:aaaa#11', 'PX', '30000') | Out-Null
  $until = [DateTimeOffset]::Now.ToUnixTimeMilliseconds() + 30000
  Assert-Equal 'renew_wrong_token' 0 (Invoke-Lua (Join-Path $tmp 'renew.lua') @($lock, $state) @('judge-a:777:aaaa#99', '30000', "$now", "$until"))
  Assert-Equal 'renew_right_token' 1 (Invoke-Lua (Join-Path $tmp 'renew.lua') @($lock, $state) @('judge-a:777:aaaa#11', '30000', "$now", "$until"))
  Assert-Equal 'state_lease_until' $until (Invoke-Redis @('HGET', $state, 'leaseUntil'))
  Invoke-Redis @('SET', $lock, 'judge-a:777:aaaa#11', 'PX', '100') | Out-Null
  Start-Sleep -Milliseconds 300
  Assert-Equal 'renew_after_expiry' 0 (Invoke-Lua (Join-Path $tmp 'renew.lua') @($lock, $state) @('judge-a:777:aaaa#11', '30000', "$now", "$until"))

  Invoke-Redis @('DEL', $state, $lock, $epochKey) | Out-Null
  if ($script:fail -gt 0) { throw "$($script:fail) fencing assertion(s) failed" }
  Write-Host '[p3] fencing/lock/renew contract: all assertions passed'
}

function Get-CounterSnapshot {
  # Sum the counters of both judge instances, so a delta is independent of which one acted.
  $snap = @{}
  foreach ($url in @($JudgeUrl, $JudgeBUrl)) {
    $m = Get-Metrics $url
    if (-not $m -or -not $m.counters) { continue }
    foreach ($p in $m.counters.PSObject.Properties) {
      $snap[$p.Name] = [long]$snap[$p.Name] + [long]$p.Value
    }
  }
  return $snap
}

function Get-CounterDelta($before, $after, [string]$name) {
  $b = if ($before.ContainsKey($name)) { [long]$before[$name] } else { 0 }
  $a = if ($after.ContainsKey($name)) { [long]$after[$name] } else { 0 }
  return $a - $b
}

function Invoke-DuplicateUnderPartition([int]$closeAfterMs) {
  # Simulate an AMQP partition while the judge is still executing: the broker requeues the
  # unacked message and the peer instance receives it as a *redelivery*. With renewal on the
  # peer keeps requeueing (lease alive) and only one execution happens; with renewal off the
  # lease expires mid-judge and the peer takes over (two executions, first one fenced).
  $metricsA = Get-Metrics $JudgeUrl
  if (-not $metricsA) { throw 'judge A not reachable' }
  $ttl = $metricsA.lease.ttlMs
  $renew = $metricsA.lease.renewEnabled
  Write-Host "[p3] judge A lease: ttlMs=$ttl renewEnabled=$renew active=$($metricsA.lease.active)"
  $peer = $null
  try { $peer = Get-Metrics $JudgeBUrl } catch { }
  if (-not $peer) { Write-Warning 'judge B not reachable: redelivery will go back to A after its reconnect delay' }
  $before = Get-CounterSnapshot

  $offsets = Get-LogOffsets
  $id = Submit-Task
  $claim = Wait-Claim $id $offsets 60
  if (-not $claim) { throw "no claim event for $id" }
  Write-Host "[p3] claim line: $claim"
  $kv = Get-Kv $claim
  $worker = $kv['worker']
  $claimAt = Get-LineTime $claim
  $procId = [int](($worker -split ':')[1])
  Write-Host "[p3] submitId=$id claimed by pid=$procId worker=$worker"
  $elapsed = ((Get-Date) - $claimAt).TotalMilliseconds
  if ($elapsed -lt $closeAfterMs) { Start-Sleep -Milliseconds ([int]($closeAfterMs - $elapsed)) }

  $closed = Close-InstanceConnections $procId
  $closedAt = Get-Date
  $instance = Get-InstanceOfPid $procId
  $closeDelayMs = [math]::Round(($closedAt - $claimAt).TotalSeconds * 1000)
  Write-Host "[p3] closed AMQP connections of instance=$instance pid=$procId ports=$($closed.ports -join ',') matched=$($closed.matched) closed=$($closed.closed) closeDelayMs=$closeDelayMs"

  $terminal = Wait-Terminal $id 180
  $doneLines = @(Get-LogLines $offsets "evt=judge\.task\.done submitId=$id ")
  $claims = @(Get-LogLines $offsets "evt=judge\.task submitId=$id ")
  $takeovers = @(Get-LogLines $offsets "evt=judge\.task\.takeover submitId=$id ")
  $fenced = @(Get-LogLines $offsets "evt=judge\.fence\.rejected submitId=$id ")
  $doneAt = if ($doneLines.Count -gt 0) { Get-LineTime $doneLines[-1] } else { $null }
  # P13: the evidence must be reproducible from the script itself. `dup` used to only read logs,
  # so the DB rows quoted in the evidence doc could not be regenerated by re-running the command.
  $db = Invoke-Mysql "SELECT status, attempt, version, epoch, worker_id FROM problem_submit WHERE id=$id"
  $after = Get-CounterSnapshot

  $result = [ordered]@{
    label        = $Label
    submitId     = $id
    ttlMs        = $ttl
    renewEnabled = $renew
    instance     = $instance
    closed       = $closed.closed
    closeDelayMs = $closeDelayMs
    terminal     = $terminal
    executions   = $claims.Count
    takeovers    = $takeovers.Count
    fenceRejected = $fenced.Count
    doneEvents   = $doneLines.Count
    db           = $db.Trim()
    leaseRenew   = Get-CounterDelta $before $after 'judge.lease.renew'
    leaseLost    = Get-CounterDelta $before $after 'judge.lease.lost'
    fenceMetric  = Get-CounterDelta $before $after 'judge.fence.rejected'
    takeoverMetric = Get-CounterDelta $before $after 'judge.takeover'
    closeToDoneSec = if ($doneAt) { [math]::Round(($doneAt - $closedAt).TotalSeconds, 2) } else { $null }
  }
  $result | ConvertTo-Json
  Write-Result $Label $result

  if ($renew -eq $true -and $claims.Count -ne 1) { throw "expected exactly 1 execution with renewal on, got $($claims.Count)" }
  # P14: the renewal-off counter-proof is a gate, not a warning. If the takeover window was
  # missed, the run must fail instead of silently reporting "1 execution" as a pass.
  if ($renew -eq $false -and $claims.Count -lt 2) {
    throw "expected >=2 executions with renewal off, got $($claims.Count) (takeover window missed; rerun with a smaller -CloseAfterMs or check the short-TTL config)"
  }
}

function Invoke-Recover([int]$runs) {
  if (-not (Wait-Health $JudgeUrl 30)) { throw 'judge A not healthy' }
  if (-not (Wait-Health $JudgeBUrl 30)) { throw 'judge B (8022) not healthy: start the peer instance first' }
  Clear-Queues
  Wait-QueuesDrained 30
  $rows = @()
  $pids = Get-InstancePids
  for ($i = 1; $i -le $runs; $i++) {
    $offsets = Get-LogOffsets
    $id = Submit-Task
    $claim = Wait-Claim $id $offsets 60
    if (-not $claim) { throw "run ${i}: no claim event for $id" }
    $kv = Get-Kv $claim
    $worker = $kv['worker']
    $procId = [int](($worker -split ':')[1])
    $instance = $pids[$procId]
    if (-not $instance) {
      $pids = Get-InstancePids
      $instance = $pids[$procId]
    }
    if (-not $instance) { throw "run ${i}: cannot map pid $procId to an instance" }
    $killAt = Get-Date
    Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    Write-Host "[p3] run $i submitId=$id instance=$instance pid=$procId killed"

    $terminal = Wait-Terminal $id 180 @($JudgeUrl, $JudgeBUrl)
    $doneLines = @(Get-LogLines $offsets "evt=judge\.task\.done submitId=$id ")
    $claims = @(Get-LogLines $offsets "evt=judge\.task submitId=$id ")
    $takeovers = @(Get-LogLines $offsets "evt=judge\.task\.takeover submitId=$id ")
    $doneAt = if ($doneLines.Count -gt 0) { Get-LineTime $doneLines[-1] } else { $null }
    $takeoverAt = if ($takeovers.Count -gt 0) { Get-LineTime $takeovers[0] } else { $null }
    $db = Invoke-Mysql "SELECT status, attempt, version, epoch, worker_id FROM problem_submit WHERE id=$id"
    $row = [ordered]@{
      run          = $i
      submitId     = $id
      killed       = $instance
      terminal     = $terminal
      claims       = $claims.Count
      # Execution count is measured by completion: a post-kill reclaim emits another
      # evt=judge.task line, but a submit must emit exactly one evt=judge.task.done.
      executions   = $doneLines.Count
      takeovers    = $takeovers.Count
      killToTakeoverSec = if ($takeoverAt) { [math]::Round(($takeoverAt - $killAt).TotalSeconds, 2) } else { $null }
      killToDoneSec = if ($doneAt) { [math]::Round(($doneAt - $killAt).TotalSeconds, 2) } else { $null }
      db           = $db.Trim()
    }
    $rows += [pscustomobject]$row
    Write-Host ("[p3] run {0}: claims={1} takeover={2}s done={3}s terminal={4}" -f $i, $row.claims, $row.killToTakeoverSec, $row.killToDoneSec, $terminal)

    Start-Instance $instance
    $healthUrl = if ($instance -eq 'A') { $JudgeUrl } else { $JudgeBUrl }
    if (-not (Wait-Health $healthUrl 300)) { throw "run ${i}: instance $instance did not come back" }
    # Restarted process has a new pid: refresh the cache outside the kill window.
    $pids = Get-InstancePids
  }

  # P14: gate the G3 claims instead of only printing numbers.
  $noTerminal = @($rows | Where-Object { $_.terminal -ne '2' -and $_.terminal -ne '3' })
  if ($noTerminal.Count -gt 0) {
    throw "recovery: $($noTerminal.Count)/$runs runs did not reach a terminal state"
  }
  $dupes = @($rows | Where-Object { $_.executions -ne 1 })
  if ($dupes.Count -gt 0) {
    $detail = ($dupes | ForEach-Object { "run $($_.run) executions=$($_.executions)" }) -join ', '
    throw "recovery: each submit must complete exactly once, got $detail"
  }

  $values = @($rows | Where-Object { $_.killToDoneSec -ne $null } | ForEach-Object { [double]$_.killToDoneSec } | Sort-Object)
  if ($values.Count -ne $runs) { throw "recovery: only $($values.Count)/$runs samples have killToDoneSec" }
  $p50 = $values[[int][math]::Floor($values.Count * 0.5)]
  $p95 = $values[[int][math]::Min($values.Count - 1, [int][math]::Ceiling($values.Count * 0.95) - 1)]
  $max = $values[$values.Count - 1]
  Write-Host ("[p3] recovery n={0} p50={1}s p95={2}s max={3}s" -f $values.Count, $p50, $p95, $max)
  if ($p95 -gt 45) { throw "recovery P95 $p95 s exceeds G3 limit 45 s" }
  Write-Result $Label ([ordered]@{
      label  = $Label
      runs   = $runs
      p50Sec = $p50
      p95Sec = $p95
      maxSec = $max
      samples = $rows
    })
}

function Invoke-E2E([int]$count) {
  $cookie = Get-Cookie
  Push-Location $repo
  try {
    $ids = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $count; $i++) {
      $resp = curl.exe -s -X POST "$JudgeUrl/judge/submit" -H 'Content-Type: application/json' -H "Cookie: $cookie" --data-binary "@$SubmitBody"
      $m = [regex]::Match([string]$resp, '"id"\s*:\s*"?(\d{10,})"?')
      if ($m.Success) { $ids.Add($m.Groups[1].Value) } else { Write-Warning "submit failed: $resp" }
    }
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
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
    $watch.Stop()
    $accepted = ($terminal.Values | Where-Object { $_ -eq '3' }).Count
    $result = [ordered]@{
      label     = $Label
      count     = $ids.Count
      terminal  = $terminal.Count
      accepted  = $accepted
      drainSec  = [math]::Round($watch.Elapsed.TotalSeconds, 2)
      judgeTps  = [math]::Round($terminal.Count / [Math]::Max(0.001, $watch.Elapsed.TotalSeconds), 2)
    }
    $result | ConvertTo-Json
    Write-Result $Label $result
  } finally { Pop-Location }
}

function Invoke-MultiDelivery([int]$copies) {
  Write-Host "[p3] multideliver copies=$copies"
  # P1 regression: the same submitId delivered N extra times must be judged exactly once.
  # Publishes extra copies straight to judge.exchange (as a broker duplicate would); unlike the
  # partition scenario these are fresh deliveries, so the worker must skip them on the lease.
  $offsets = Get-LogOffsets
  $id = Submit-Task
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
  # PS 5.1 mangles inline JSON quotes for curl.exe, so the body goes through a file.
  $pubPath = Join-Path $repo '.runtime/p3-publish-body.json'
  for ($i = 0; $i -lt $copies; $i++) {
    $body = [ordered]@{
      properties       = @{ content_type = 'application/json'; delivery_mode = 2 }
      routing_key      = 'judge.task.normal'
      payload          = $payload
      payload_encoding = 'string'
    } | ConvertTo-Json -Compress
    Set-Content -Path $pubPath -Value $body -Encoding ASCII
    $out = curl.exe -s -u $RabbitAuth -H 'Content-Type: application/json' --data-binary "@$pubPath" "$Rabbit/api/exchanges/%2F/judge.exchange/publish"
    if ([string]$out -notmatch '"routed"\s*:\s*true') { Write-Warning "publish $i -> $out" }
  }
  $terminal = Wait-Terminal $id 180
  $claims = @(Get-LogLines $offsets "evt=judge\.task submitId=$id ")
  $done = @(Get-LogLines $offsets "evt=judge\.task\.done submitId=$id ")
  $skips = @(Get-LogLines $offsets "evt=judge\.task\.skip submitId=$id ")
  $takeovers = @(Get-LogLines $offsets "evt=judge\.task\.takeover submitId=$id ")
  $db = Invoke-Mysql "SELECT status, attempt, version, epoch FROM problem_submit WHERE id=$id"
  $result = [ordered]@{
    label      = $Label
    submitId   = $id
    copies     = $copies
    terminal   = $terminal
    executions = $claims.Count
    doneEvents = $done.Count
    skips      = $skips.Count
    takeovers  = $takeovers.Count
    db         = $db.Trim()
  }
  $result | ConvertTo-Json
  Write-Result $Label $result
  if ($claims.Count -ne 1) { throw "expected exactly 1 execution, got $($claims.Count)" }
}

function Invoke-Cleanup {
  # P8: -ProblemId scopes the delete so a cleanup run cannot wipe unrelated user submissions.
  $where = @()
  if ($Since) { $where += "create_time >= '$Since'" }
  if ($ProblemId -gt 0) { $where += "problem_id = $ProblemId" }
  if ($where.Count -eq 0) { throw 'cleanup needs -Since "yyyy-MM-dd HH:mm:ss" or -ProblemId <id>' }
  $filter = $where -join ' AND '
  Write-Host "[p3] delete submit rows ($filter)"
  Invoke-Mysql "DELETE FROM problem_submit WHERE $filter" | Out-Null
  if ($ProblemId -gt 0) {
    Invoke-Mysql "UPDATE problem p SET submit_num=(SELECT COUNT(*) FROM problem_submit s WHERE s.problem_id=p.id), accepted_num=(SELECT COUNT(*) FROM problem_submit s WHERE s.problem_id=p.id AND s.status=3) WHERE p.id=$ProblemId" | Out-Null
  } else {
    Invoke-Mysql "UPDATE problem p SET submit_num=(SELECT COUNT(*) FROM problem_submit s WHERE s.problem_id=p.id), accepted_num=(SELECT COUNT(*) FROM problem_submit s WHERE s.problem_id=p.id AND s.status=3)" | Out-Null
  }
  Write-Host '[p3] clear redis keys'
  Invoke-Redis @('EVAL', "local n=0; for _,pat in ipairs({'judge:state:*','judge:lock:*','judge:epoch:*'}) do local ks=redis.call('KEYS',pat); for i=1,#ks do redis.call('DEL',ks[i]); n=n+1 end end; return n", '0')
  Write-Host '[p3] purge queues'
  Clear-Queues
  Start-Sleep -Seconds 1
  Clear-Queues
  Wait-QueuesDrained
  Show-State
}

switch ($Scenario) {
  'state' { Show-State }
  'fence' { Invoke-Fence }
  'dup' { Invoke-DuplicateUnderPartition -closeAfterMs $CloseAfterMs }
  'multideliver' { Invoke-MultiDelivery -copies $Copies }
  'recover' { Invoke-Recover -runs $Runs }
  'metrics' {
    $cookie = Get-Cookie
    Write-Host '[p3] judge A:'
    curl.exe -s "$JudgeUrl/judge/metrics" -H "Cookie: $cookie"
    Write-Host ''
    Write-Host '[p3] judge B:'
    curl.exe -s "$JudgeBUrl/judge/metrics" -H "Cookie: $cookie"
    Write-Host ''
  }
  'e2e' { Invoke-E2E -count $Count }
  'cleanup' { Invoke-Cleanup }
}


