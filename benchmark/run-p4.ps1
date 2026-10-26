param(
  [ValidateSet('all', 'events', 'episode', 'pattern', 'context', 'metrics', 'limit', 'batch', 'cleanup')]
  [string]$Scenario = 'all',
  [string]$Label = 'p4-lab',
  [long]$Uid = 990000000000000777,
  [long]$ProbeProblemId = 990002,
  # Must match the judge's --oj.memory.episode-interval-ms (documented in the evidence)
  [int]$EpisodeIntervalMs = 10000,
  [string]$Mysql = 'D:\mysql\mysql\bin\mysql.exe',
  [string]$MysqlUser = 'root',
  [string]$MysqlPassword = $env:OJ_MYSQL_PASSWORD,
  [string]$JudgeUrl = 'http://127.0.0.1:8012',
  [string]$RedisCli = 'D:\developer\env\Redis-x64-5.0.14.1\redis-cli.exe'
)

# P4 memory pipeline checks: event coverage, idempotency, whitelist, episode materialisation,
# pattern extraction, context assembly, metrics.
# ASCII only on purpose: Windows PowerShell 5.1 parses .ps1 as ANSI, non-ASCII breaks parsing.
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$script:fail = 0
$script:assertions = 0
$script:eventSeq = 0
$script:episodeIntervalMs = $EpisodeIntervalMs
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

function Get-Cookie { return (Get-Content (Join-Path $repo '.runtime/cookie.txt') -Raw) }

# Chinese expectations live in a UTF-8 JSON fixture: Windows PowerShell 5.1 reads .ps1 as ANSI,
# so non-ASCII literals inside this script would be mangled (and would break parsing).
$script:expected = Get-Content (Join-Path $PSScriptRoot 'fixtures/p4-expected.json') -Raw -Encoding UTF8 | ConvertFrom-Json

function Invoke-Api([string]$method, [string]$path, [string]$bodyPath = '') {
  $cookie = Get-Cookie
  $curlArgs = @('-s', '-X', $method, "$JudgeUrl$path", '-H', "Cookie: $cookie")
  if ($bodyPath) {
    $curlArgs += @('-H', 'Content-Type: application/json', '--data-binary', "@$bodyPath")
  }
  $raw = & curl.exe @curlArgs
  return [pscustomobject]@{ raw = [string]$raw; json = $(try { [string]$raw | ConvertFrom-Json } catch { $null }) }
}

function Assert-Equal([string]$name, $expected, $actual) {
  $script:assertions++
  if ("$expected" -eq "$actual") {
    Write-Host ("[p4] PASS {0} = {1}" -f $name, $actual)
  } else {
    Write-Host ("[p4] FAIL {0} expected={1} actual={2}" -f $name, $expected, $actual)
    $script:fail++
  }
}

function Assert-True([string]$name, [bool]$ok, [string]$detail = '') {
  $script:assertions++
  if ($ok) {
    Write-Host ("[p4] PASS {0} {1}" -f $name, $detail)
  } else {
    Write-Host ("[p4] FAIL {0} {1}" -f $name, $detail)
    $script:fail++
  }
}

function Wait-Until([scriptblock]$probe, [int]$timeoutSec = 90, [int]$intervalMs = 1000) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $value = & $probe
    if ($value) { return $value }
    Start-Sleep -Milliseconds $intervalMs
  }
  return $null
}

function Write-EventBody([string]$path, [object[]]$events) {
  $payload = @{ events = $events } | ConvertTo-Json -Depth 6 -Compress
  Set-Content -Path $path -Value $payload -Encoding ASCII
}

function Get-EventCount([string]$where) {
  return [int](Invoke-Mysql "SELECT COUNT(*) FROM learning_event WHERE $where")
}

# ---------------- fixtures ----------------

function Reset-Fixture {
  Invoke-Mysql "DELETE FROM learning_event WHERE uid = $Uid" | Out-Null
  Invoke-Mysql "DELETE FROM learning_episode WHERE uid = $Uid" | Out-Null
  Invoke-Mysql "DELETE FROM learning_pattern WHERE uid = $Uid" | Out-Null
}

function Ensure-ProbeProblem {
  # Clone an existing problem row so the fixture does not depend on the full column list.
  $exists = [int](Invoke-Mysql "SELECT COUNT(*) FROM problem WHERE id = $ProbeProblemId")
  if ($exists -eq 0) {
    Invoke-Mysql "DROP TEMPORARY TABLE IF EXISTS p4_probe_src; CREATE TEMPORARY TABLE p4_probe_src AS SELECT * FROM problem WHERE id = 990001 LIMIT 1; UPDATE p4_probe_src SET id = $ProbeProblemId, title = 'P4 Memory Probe'; INSERT INTO problem SELECT * FROM p4_probe_src;" | Out-Null
  }
  # 0xE695B0E7BB84 is the utf-8 bytes for the tag name: keeping the SQL ASCII-only avoids the PS 5.1 ANSI trap
  Invoke-Mysql "UPDATE problem SET tags = JSON_ARRAY(CONVERT(0xE695B0E7BB84 USING utf8mb4)) WHERE id = $ProbeProblemId" | Out-Null
}

function New-EventSql([string]$eventId, [string]$type, [long]$problemId, [string]$offsetMinutes,
    [string]$payloadExpression = 'JSON_OBJECT()', [long]$submitId = 0) {
  $script:eventSeq = [int]$script:eventSeq + 1
  $id = [long]"9900000000000$('{0:D5}' -f $script:eventSeq)"
  return "INSERT INTO learning_event (id, event_id, uid, event_type, problem_id, submit_id, source, payload, occurred_at, created_at) " +
    "VALUES ($id, '$eventId', $Uid, '$type', $problemId, $submitId, 'SERVER', $payloadExpression, DATE_ADD(NOW(3), INTERVAL $offsetMinutes MINUTE), NOW(3))"
}

function Seed-SyntheticSessions {
  # S1 (closed, 5 min): 3 submissions, 3 timeouts -> WA, attempts=3, firstFail=TLE
  $rows = @(
    (New-EventSql "p4-s1-$Uid-1" 'submit' $ProbeProblemId '-180'),
    (New-EventSql "p4-s1-$Uid-2" 'wa' $ProbeProblemId '-179' "JSON_OBJECT('judgeStatus', 3, 'pass', 0, 'total', 2)"),
    (New-EventSql "p4-s1-$Uid-3" 'submit' $ProbeProblemId '-178'),
    (New-EventSql "p4-s1-$Uid-4" 'wa' $ProbeProblemId '-177' "JSON_OBJECT('judgeStatus', 3, 'pass', 0, 'total', 2)"),
    (New-EventSql "p4-s1-$Uid-5" 'submit' $ProbeProblemId '-176'),
    (New-EventSql "p4-s1-$Uid-6" 'wa' $ProbeProblemId '-175' "JSON_OBJECT('judgeStatus', 3, 'pass', 0, 'total', 2)")
  )
  # S2 (closed, 1 min): submit -> run -> ac, attempts=1
  $rows += @(
    (New-EventSql "p4-s2-$Uid-1" 'submit' $ProbeProblemId '-60'),
    (New-EventSql "p4-s2-$Uid-2" 'run' $ProbeProblemId '-60'),
    (New-EventSql "p4-s2-$Uid-3" 'ac' $ProbeProblemId '-60' "JSON_OBJECT('judgeStatus', 0, 'pass', 2, 'total', 2)")
  )
  # S3 (open): must NOT be materialised
  $rows += (New-EventSql "p4-s3-$Uid-1" 'submit' $ProbeProblemId '-2')
  Invoke-Mysql ($rows -join '; ') | Out-Null
}

# ---------------- scenarios ----------------

function Scenario-Events {
  Write-Host '[p4] --- events: client report, idempotency, whitelist'
  Ensure-ProbeProblem
  $bodyPath = Join-Path $repo '.runtime/p4-events-body.json'
  $stamp = [DateTime]::Now.ToString('HHmmssfff')
  Write-EventBody $bodyPath @(
    @{ eventId = "p4-dwell-$stamp"; eventType = 'dwell'; problemId = $ProbeProblemId; payload = @{ durationMs = 65000 } },
    @{ eventId = "p4-copy-$stamp"; eventType = 'copy_code'; problemId = $ProbeProblemId; payload = @{ chars = 321 } },
    @{ eventId = "p4-solution-$stamp"; eventType = 'view_solution'; problemId = $ProbeProblemId }
  )
  $res = Invoke-Api 'POST' '/judge/learning/event' $bodyPath
  Assert-Equal 'client.accepted' 3 $res.json.data

  # dwell is also a session-end signal: the episode must be materialised right after it lands
  $dwellDelay = Wait-Until {
    $row = Invoke-Mysql "SELECT TIMESTAMPDIFF(SECOND, ep.ended_at, ep.created_at) FROM learning_episode ep JOIN learning_event e ON e.uid = ep.uid AND e.problem_id = ep.problem_id AND e.episode_id = ep.id WHERE e.event_type = 'dwell' AND ep.problem_id = $ProbeProblemId ORDER BY ep.created_at DESC LIMIT 1"
    if ($row) { [string]($row -join '') }
  } 30 500
  Assert-True 'dwell.fast_path' ($null -ne $dwellDelay -and [int]$dwellDelay -le 5) "delaySec=$dwellDelay"

  Start-Sleep -Seconds 2
  Assert-Equal 'client.dwell.rows' 1 (Get-EventCount "event_id = 'p4-dwell-$stamp'")
  Assert-Equal 'client.dwell.payload' '{"durationMs": 65000}' ((Invoke-Mysql "SELECT payload FROM learning_event WHERE event_id = 'p4-dwell-$stamp'") -join '')

  # Idempotency: same eventId twice -> still one row
  $res = Invoke-Api 'POST' '/judge/learning/event' $bodyPath
  Start-Sleep -Seconds 2
  Assert-Equal 'client.duplicate.rows' 1 (Get-EventCount "event_id = 'p4-dwell-$stamp'")

  # Whitelist: a client must not be able to forge submit/ac
  $forgePath = Join-Path $repo '.runtime/p4-events-forge.json'
  Write-EventBody $forgePath @(
    @{ eventId = "p4-forge-$stamp"; eventType = 'submit'; problemId = $ProbeProblemId }
  )
  $res = Invoke-Api 'POST' '/judge/learning/event' $forgePath
  Assert-Equal 'client.forge.accepted' 0 $res.json.data
  Start-Sleep -Seconds 1
  Assert-Equal 'client.forge.rows' 0 (Get-EventCount "event_id = 'p4-forge-$stamp'")

  Write-Host '[p4] --- events: server emitters (view/run/submit/ac)'
  $cookie = Get-Cookie
  Invoke-Api 'GET' "/judge/problem/$ProbeProblemId/vo/safe" | Out-Null
  Start-Sleep -Seconds 2

  $runBody = Join-Path $repo '.runtime/p4-run-body.json'
  Set-Content -Path $runBody -Encoding ASCII -Value ('{"code":"public class Main { public static void main(String[] a) { System.out.println(1); } }","language":"Java","inputList":["1 2"],"problemId":' + $ProbeProblemId + '}')
  Invoke-Api 'POST' '/judge/run' $runBody | Out-Null
  Start-Sleep -Seconds 3

  $submitBody = Join-Path $repo '.runtime/p4-submit-body.json'
  Set-Content -Path $submitBody -Encoding ASCII -Value ('{"code":"import java.util.*;\npublic class Main {\n  public static void main(String[] a) { Scanner s = new Scanner(System.in); System.out.println(s.nextInt() + s.nextInt()); }\n}","language":"Java","problemId":1}')
  $res = Invoke-Api 'POST' '/judge/submit' $submitBody
  $submitId = [regex]::Match($res.raw, '"id"\s*:\s*"?(\d{10,})"?').Groups[1].Value
  Assert-True 'submit.id' ($submitId -ne '') "submitId=$submitId"
  $terminal = Wait-Until { $s = Invoke-Api 'GET' "/judge/submit/$submitId/status"; if ($s.raw -match '"status":\s*([23])') { $Matches[1] } } 120 500
  Assert-True 'submit.terminal' ($terminal -ne $null) "status=$terminal"

  $resultType = if ("$terminal" -eq '3') { 'ac' } else { 'wa' }
  $row = Wait-Until { $c = Get-EventCount "event_type = '$resultType' AND submit_id = $submitId"; if ($c -gt 0) { $c } } 30 500
  Assert-True "server.$resultType" ($row -ne $null) "rows=$row"

  # A deliberately wrong answer guarantees a `wa` event: coverage must not depend on the first verdict
  $wrongBody = Join-Path $repo '.runtime/p4-submit-wrong.json'
  Set-Content -Path $wrongBody -Encoding ASCII -Value '{"code":"public class Main { public static void main(String[] a) { System.out.println(0); } }","language":"Java","problemId":1}'
  $res = Invoke-Api 'POST' '/judge/submit' $wrongBody
  $wrongId = [regex]::Match($res.raw, '"id"\s*:\s*"?(\d{10,})"?').Groups[1].Value
  $wrongTerminal = Wait-Until { $s = Invoke-Api 'GET' "/judge/submit/$wrongId/status"; if ($s.raw -match '"status":\s*([23])') { $Matches[1] } } 120 500
  $waRow = Wait-Until { $c = Get-EventCount "event_type = 'wa' AND submit_id = $wrongId"; if ($c -gt 0) { $c } } 60 500
  Assert-True 'server.wa' ($waRow -ne $null) "rows=$waRow terminal=$wrongTerminal"

  $viewRow = Get-EventCount "event_type = 'view_problem' AND problem_id = $ProbeProblemId AND uid <> $Uid"
  Assert-True 'server.view_problem' ($viewRow -gt 0) "rows=$viewRow"
  $runRow = Get-EventCount "event_type = 'run' AND problem_id = $ProbeProblemId AND uid <> $Uid"
  Assert-True 'server.run' ($runRow -gt 0) "rows=$runRow"

  # Coverage: every one of the eight types must exist in the window
  $types = @('view_problem', 'run', 'submit', 'ac', 'wa', 'view_solution', 'copy_code', 'dwell')
  $present = 0
  foreach ($t in $types) {
    $c = Get-EventCount "event_type = '$t'"
    if ($c -gt 0) { $present++ } else { Write-Host "[p4] missing event type: $t" }
  }
  Assert-Equal 'coverage.types' 8 $present
}

function Scenario-Episode {
  Write-Host '[p4] --- episode: sweep materialises closed sessions only'
  Reset-Fixture
  Ensure-ProbeProblem
  $seededAt = Get-Date
  Seed-SyntheticSessions
  $seedUncovered = [int](Invoke-Mysql "SELECT COUNT(*) FROM learning_event WHERE uid = $Uid AND episode_id IS NULL")
  Assert-Equal 'seed.events' 10 $seedUncovered

  $built = Wait-Until { $c = [int](Invoke-Mysql "SELECT COUNT(*) FROM learning_episode WHERE uid = $Uid"); if ($c -ge 2) { $c } } 120 2000
  Assert-True 'episode.built' ($built -eq 2) "count=$built"
  # Sweep latency: from seeding to the tick that materialised the closed sessions.
  # The raw created_at - ended_at metric is inflated by the backdated fixture, so measure wall clock instead.
  $sweepSec = [int]((Get-Date) - $seededAt).TotalSeconds
  Assert-True 'episode.sweep.latency' ($sweepSec -le 30) "sweepSec=$sweepSec (interval=${script:episodeIntervalMs}ms)"

  $rows = @(Invoke-Mysql "SELECT outcome, attempt_count, run_count, event_count, summary FROM learning_episode WHERE uid = $Uid ORDER BY started_at")
  Assert-Equal 'episode.s1.outcome' 'WA' ($rows[0] -split "`t")[0]
  Assert-Equal 'episode.s1.attempts' 3 ($rows[0] -split "`t")[1]
  Assert-Equal 'episode.s1.events' 6 ($rows[0] -split "`t")[3]
  Assert-Equal 'episode.s1.summary' $script:expected.s1Summary ($rows[0] -split "`t")[4]
  Assert-Equal 'episode.s2.outcome' 'AC' ($rows[1] -split "`t")[0]
  Assert-Equal 'episode.s2.run' 1 ($rows[1] -split "`t")[2]
  Assert-Equal 'episode.s2.summary' $script:expected.s2Summary ($rows[1] -split "`t")[4]

  # The open session must stay uncovered
  $uncovered = [int](Invoke-Mysql "SELECT COUNT(*) FROM learning_event WHERE uid = $Uid AND episode_id IS NULL")
  Assert-Equal 'episode.open.uncovered' 1 $uncovered

  # Idempotency: another sweep must not create a third episode
  Start-Sleep -Seconds 20
  $again = [int](Invoke-Mysql "SELECT COUNT(*) FROM learning_episode WHERE uid = $Uid")
  Assert-Equal 'episode.idempotent' 2 $again

  Write-Host '[p4] --- episode: AC fast path'
  $submitBody = Join-Path $repo '.runtime/p4-submit-body.json'
  $startedAt = Get-Date
  $res = Invoke-Api 'POST' '/judge/submit' $submitBody
  $submitId = [regex]::Match($res.raw, '"id"\s*:\s*"?(\d{10,})"?').Groups[1].Value
  $terminal = Wait-Until { $s = Invoke-Api 'GET' "/judge/submit/$submitId/status"; if ($s.raw -match '"status":\s*([23])') { $Matches[1] } } 120 500
  $fast = Wait-Until {
    $row = Invoke-Mysql "SELECT TIMESTAMPDIFF(SECOND, ep.ended_at, ep.created_at) FROM learning_episode ep JOIN learning_event e ON e.uid = ep.uid AND e.problem_id = ep.problem_id AND e.episode_id = ep.id WHERE e.event_type IN ('ac','wa') AND e.submit_id = $submitId ORDER BY ep.created_at DESC LIMIT 1"
    # Return a string: a delay of 0 seconds must still count as "materialised"
    if ($row) { [string]($row -join '') }
  } 30 500
  Assert-True 'ac.fast_path.materialised' ($null -ne $fast) "delaySec=$fast"
  Assert-True 'ac.fast_path.under_5s' ($null -ne $fast -and [int]$fast -le 5) "delaySec=$fast"
  $totalSec = [int]((Get-Date) - $startedAt).TotalSeconds
  Write-Host "[p4] ac.path.totalSec=$totalSec terminal=$terminal"
}

function Scenario-Pattern {
  Write-Host '[p4] --- pattern: three rule-based patterns from episodes'
  $episodes = [int](Invoke-Mysql "SELECT COUNT(*) FROM learning_episode WHERE uid = $Uid")
  Assert-True 'pattern.input.episodes' ($episodes -ge 2) "episodes=$episodes"

  $rows = Wait-Until { $r = Invoke-Mysql "SELECT pattern_type, confidence, description FROM learning_pattern WHERE uid = $Uid ORDER BY pattern_type"; if (@($r).Count -ge 3) { $r } } 120 2000
  Assert-True 'pattern.count' (@($rows).Count -eq 3) "count=$(@($rows).Count)"

  $map = @{}
  foreach ($row in @($rows)) {
    $parts = $row -split "`t"
    $map[$parts[0]] = $parts[1..2]
  }
  Assert-Equal 'pattern.error_focus.confidence' '1.000' $map['ERROR_FOCUS'][0]
  Assert-True 'pattern.error_focus.text' ($map['ERROR_FOCUS'][1] -like "*$($script:expected.errorFocusText)*") $map['ERROR_FOCUS'][1]
  # 4 submissions, 1 AC episode -> fail rate 0.75
  Assert-Equal 'pattern.weak_tag.confidence' '0.750' $map['WEAK_TAG'][0]
  Assert-True 'pattern.weak_tag.tag' ($map['WEAK_TAG'][1] -like "*$($script:expected.weakTagText)*") $map['WEAK_TAG'][1]
  # 1 accepted episode, 1 attempt each -> avg 1.00; contract: min(1, 1/avg) = 1.000
  Assert-Equal 'pattern.attempt_style.confidence' '1.000' $map['ATTEMPT_STYLE'][0]
  Assert-True 'pattern.attempt_style.text' ($map['ATTEMPT_STYLE'][1] -like "*$($script:expected.attemptStyleText)*") $map['ATTEMPT_STYLE'][1]

  # Rebuild must overwrite the same row and bump version, not duplicate
  $versions = Wait-Until {
    $v = (Invoke-Mysql "SELECT GROUP_CONCAT(version) FROM learning_pattern WHERE uid = $Uid") -join ''
    if ($v -and $v -notmatch '^1,1,1$') { $v }
  } 90 5000
  Assert-True 'pattern.versioned' ($null -ne $versions) "versions=$versions"
  Assert-Equal 'pattern.no_duplicates' 3 ([int](Invoke-Mysql "SELECT COUNT(*) FROM learning_pattern WHERE uid = $Uid"))

  # A pattern type that no longer qualifies must be deleted on the next rebuild (not served forever)
  Invoke-Mysql "INSERT INTO learning_pattern (id, uid, pattern_type, description, evidence, confidence, version, created_at, updated_at) VALUES (9900000000000999, $Uid, 'STALE_TEST', 'stale', JSON_OBJECT(), 0.500, 1, NOW(3), NOW(3))" | Out-Null
  $staleGone = Wait-Until {
    $c = [int](Invoke-Mysql "SELECT COUNT(*) FROM learning_pattern WHERE uid = $Uid AND pattern_type = 'STALE_TEST'")
    if ($c -eq 0) { 'gone' }
  } 90 2000
  Assert-True 'pattern.stale_deleted' ($null -ne $staleGone) 'expired type must be removed by the rebuild'
}

function Scenario-Context {
  Write-Host '[p4] --- context: shape, trimming, token estimate'
  $res = Invoke-Api 'GET' "/judge/learning/context?problemId=$ProbeProblemId"
  Assert-Equal 'context.code' 200 $res.json.code
  $data = $res.json.data
  Assert-True 'context.problem' ($null -ne $data.problem -and $data.problem.id -eq $ProbeProblemId) "title=$($data.problem.title)"
  Assert-True 'context.tokenEstimate' ($data.tokenEstimate -gt 0) "tokens=$($data.tokenEstimate)"
  Assert-True 'context.episodes.array' ($data.episodes -is [array]) "episodes=$(@($data.episodes).Count)"
  Assert-Equal 'context.episodes.cap' 0 ([math]::Max(0, @($data.episodes).Count - 3))
  Assert-Equal 'context.patterns.cap' 0 ([math]::Max(0, @($data.patterns).Count - 3))
  Assert-Equal 'context.skills.empty' 0 @($data.skills).Count

  # Episode summaries must be the same strings the episode scenario asserted
  $summaries = @($data.episodes | ForEach-Object { $_.summary })
  Assert-True 'context.summary.present' ($summaries.Count -gt 0 -and $summaries[0].Length -gt 10) "first=$($summaries[0])"

  $metrics = (Invoke-Api 'GET' '/judge/learning/metrics').json.data
  Assert-True 'context.budget' ($metrics -ne $null -and $data.tokenEstimate -le 1200) "tokens=$($data.tokenEstimate)"
  Write-Host ("[p4] context.tokens={0} truncated={1} episodes={2} patterns={3}" -f $data.tokenEstimate, $data.truncated, @($data.episodes).Count, @($data.patterns).Count)
}

function Scenario-Metrics {
  Write-Host '[p4] --- metrics: DB-derived view + counters'
  $res = Invoke-Api 'GET' '/judge/learning/metrics'
  Assert-Equal 'metrics.code' 200 $res.json.code
  $data = $res.json.data
  Assert-Equal 'metrics.enabled' $true $data.enabled
  Assert-Equal 'metrics.transport' 'amqp' $data.transport
  Assert-True 'metrics.events.total' ($data.events.total -gt 0) "total=$($data.events.total)"
  Assert-True 'metrics.episodes.count' ($data.episodes.count -gt 0) "count=$($data.episodes.count)"
  Assert-True 'metrics.delay.sample' ($data.episodes.sample -gt 0) "sample=$($data.episodes.sample)"

  $types = @($data.events.byType.PSObject.Properties.Name)
  Assert-True 'metrics.byType' ($types.Count -ge 6) "types=$($types -join ',')"

  # Metrics must be a view of the DB, not a separate counter that can drift
  $dbEvents = [int](Invoke-Mysql 'SELECT COUNT(*) FROM learning_event')
  Assert-Equal 'metrics.events.total.matches_db' $dbEvents $data.events.total
  $dbEpisodes = [int](Invoke-Mysql 'SELECT COUNT(*) FROM learning_episode')
  Assert-Equal 'metrics.episodes.count.matches_db' $dbEpisodes $data.episodes.count

  # Contract 0004 section 7: counters live on this endpoint too
  Assert-True 'metrics.counter.published' ($null -ne $data.counters.'memory.event.published') "published=$($data.counters.'memory.event.published')"
  Assert-True 'metrics.counter.built' ($null -ne $data.counters.'memory.episode.built') "built=$($data.counters.'memory.episode.built')"

  $judge = (Invoke-Api 'GET' '/judge/metrics').json.data
  $counters = $judge.counters
  Assert-True 'metrics.counter.accepted' ($counters.'memory.event.accepted' -gt 0) "accepted=$($counters.'memory.event.accepted')"
  # Drops must be explained: in this run the only drop source is the rate-limit scenario
  $dropped = [int]($counters.'memory.event.dropped')
  $limited = [int]($counters.'memory.event.rate_limited')
  Assert-True 'metrics.counter.dropped_matches_rate_limited' ($dropped -eq $limited) "dropped=$dropped rateLimited=$limited"
  $lease = $judge.lease
  Assert-True 'metrics.lease.still_present' ($null -ne $lease) "ttl=$($lease.ttlMs)"
}

function Scenario-Limit {
  Write-Host '[p4] --- client rate limit: over-limit events are dropped, request still succeeds'
  # Make sure the request lands in the same minute bucket we prefill (avoid the boundary race)
  $seconds = [int](Get-Date -Format 'ss')
  if ($seconds -ge 50) { Start-Sleep -Seconds (61 - $seconds) }
  # Client events belong to the logged-in cookie user, not to the synthetic fixture uid
  $rateUid = (Invoke-Mysql "SELECT uid FROM learning_event WHERE problem_id = $ProbeProblemId AND event_id LIKE 'p4-dwell-%' ORDER BY created_at DESC LIMIT 1") -join ''
  if (-not $rateUid) { $rateUid = $Uid }
  $minute = [long][math]::Floor([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 60000)
  $key = "memory:rate:${rateUid}:${minute}"
  & $RedisCli -n 12 SET $key 600 | Out-Null
  $stamp = [DateTime]::Now.ToString('HHmmssfff')
  $bodyPath = Join-Path $repo '.runtime/p4-limit-body.json'
  Write-EventBody $bodyPath @(
    @{ eventId = "p4-limit-$stamp"; eventType = 'view_solution'; problemId = $ProbeProblemId }
  )
  $res = Invoke-Api 'POST' '/judge/learning/event' $bodyPath
  Assert-Equal 'limit.http.code' 200 $res.json.code
  Assert-Equal 'limit.accepted' 0 $res.json.data
  Start-Sleep -Seconds 1
  Assert-Equal 'limit.rows' 0 (Get-EventCount "event_id = 'p4-limit-$stamp'")
  & $RedisCli -n 12 DEL $key | Out-Null
}

function Scenario-Batch {
  Write-Host '[p4] --- client batch: more than 20 events in one request is rejected'
  $stamp = [DateTime]::Now.ToString('HHmmssfff')
  $events = @()
  for ($i = 0; $i -lt 21; $i++) {
    $events += @{ eventId = "p4-batch-$stamp-$i"; eventType = 'view_solution'; problemId = $ProbeProblemId }
  }
  $bodyPath = Join-Path $repo '.runtime/p4-batch-body.json'
  Write-EventBody $bodyPath $events
  $res = Invoke-Api 'POST' '/judge/learning/event' $bodyPath
  Assert-True 'batch.rejected' ($null -ne $res.json -and "$($res.json.code)" -ne '200') "code=$($res.json.code)"
}

function Scenario-Cleanup {
  Write-Host '[p4] --- cleanup: remove synthetic fixtures only'
  Reset-Fixture
  Invoke-Mysql "DELETE FROM problem WHERE id = $ProbeProblemId" | Out-Null
  Assert-Equal 'cleanup.events' 0 ([int](Invoke-Mysql "SELECT COUNT(*) FROM learning_event WHERE uid = $Uid"))
  Assert-Equal 'cleanup.episodes' 0 ([int](Invoke-Mysql "SELECT COUNT(*) FROM learning_episode WHERE uid = $Uid"))
  Assert-Equal 'cleanup.problem' 0 ([int](Invoke-Mysql "SELECT COUNT(*) FROM problem WHERE id = $ProbeProblemId"))
}

# ---------------- main ----------------

$startedAt = Get-Date
$result = [ordered]@{ scenario = $Scenario; label = $Label; uid = $Uid; startedAt = $startedAt.ToString('s') }

switch ($Scenario) {
  'events' { Scenario-Events }
  'episode' { Scenario-Episode }
  'pattern' { Scenario-Pattern }
  'context' { Scenario-Context }
  'metrics' { Scenario-Metrics }
  'limit' { Scenario-Limit }
  'batch' { Scenario-Batch }
  'cleanup' { Scenario-Cleanup }
  'all' {
    Ensure-ProbeProblem
    Scenario-Events
    Scenario-Batch
    Scenario-Limit
    Scenario-Episode
    Scenario-Pattern
    Scenario-Context
    Scenario-Metrics
  }
}

$result.fail = $script:fail
$result.assertions = $script:assertions
$result.durationSec = [int]((Get-Date) - $startedAt).TotalSeconds
$outDir = Join-Path $repo 'benchmark/results'
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }
$outPath = Join-Path $outDir "$Label-$Scenario.json"
Set-Content -Path $outPath -Value ($result | ConvertTo-Json -Depth 4) -Encoding ASCII
Write-Host ("[p4] done scenario={0} assertions={1} fail={2} durationSec={3} -> {4}" -f $Scenario, $script:assertions, $script:fail, $result.durationSec, $outPath)
if ($script:fail -gt 0) { exit 1 }
