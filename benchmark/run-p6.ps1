# P6 evidence script: AI tutor through the gateway.
#
# ASCII only. Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI, and a
# non-ASCII comment can swallow the following line (see p5 ticket 10).
#
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\benchmark\run-p6.ps1 -Scenario smoke
#
# It mints a short-lived HS256 token with the same secret as the user service
# and registers it in Redis db 12, so no manual login is needed.

param(
    [ValidateSet('smoke', 'context', 'ladder', 'guard', 'skill', 'skill-extract', 'skill-use', 'summary', 'session', 'related', 'length', 'attribution', 'ab', 'all')]
    [string]$Scenario = 'smoke',
    [string]$Gateway = 'http://127.0.0.1:8024',
    [string]$JudgeDirect = 'http://127.0.0.1:8012/judge',
    [string]$AiBase = 'http://127.0.0.1:8026',
    [long]$Uid = 9900000001,
    [string]$MysqlExe = 'mysql',
    [string]$PythonExe = 'python',
    [string]$RedisHost = '127.0.0.1',
    [int]$RedisPort = 6379,
    [int]$RedisDb = 12
)

$ErrorActionPreference = 'Stop'
$script:assertions = 0
$script:failures = 0
$script:tmp = Join-Path (Get-Location) '.runtime/tmp'
New-Item -ItemType Directory -Force $script:tmp | Out-Null

$JwtSecret = $env:OJ_JWT_SECRET
if (-not $JwtSecret) {
    throw 'OJ_JWT_SECRET is not set: it must match oj.user.secret-key in the gateway and judge config'
}

function Check([string]$name, [bool]$ok, [string]$detail = '') {
    $script:assertions++
    if (-not $ok) {
        $script:failures++
        $suffix = if ($detail) { " -- $detail" } else { '' }
        Write-Host ("FAIL  {0}{1}" -f $name, $suffix)
    } else {
        Write-Host ("ok    {0}" -f $name)
    }
}

function To-B64Url([byte[]]$bytes) {
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-TokenFor([long]$tokenUid, [string]$role = 'admin') {
    $header = To-B64Url([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
    $payloadJson = '{"uid":' + $tokenUid + ',"userRole":"' + $role + '"}'
    $payload = To-B64Url([Text.Encoding]::UTF8.GetBytes($payloadJson))
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    $hmac.Key = [Text.Encoding]::UTF8.GetBytes($JwtSecret)
    $sig = To-B64Url($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($header + '.' + $payload)))
    return $header + '.' + $payload + '.' + $sig
}

function Encode-Redis([string[]]$parts) {
    $sb = New-Object Text.StringBuilder
    [void]$sb.Append('*' + $parts.Count + "`r`n")
    foreach ($p in $parts) {
        [void]$sb.Append('$' + [Text.Encoding]::UTF8.GetByteCount($p) + "`r`n" + $p + "`r`n")
    }
    return $sb.ToString()
}

# One connection: SELECT then SET, otherwise the token lands in db 0.
function Register-Token([string]$token, [long]$tokenUid) {
    $client = New-Object Net.Sockets.TcpClient($RedisHost, $RedisPort)
    try {
        $stream = $client.GetStream()
        $select = Encode-Redis @('SELECT', [string]$RedisDb)
        $set = Encode-Redis @('SET', ('user:token:' + $tokenUid), $token)
        $bytes = [Text.Encoding]::UTF8.GetBytes($select + $set)
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush()
        Start-Sleep -Milliseconds 150
        $buffer = New-Object byte[] 256
        $read = $stream.Read($buffer, 0, $buffer.Length)
        $reply = [Text.Encoding]::UTF8.GetString($buffer, 0, $read)
        if ($reply -notmatch '\+OK') { throw "redis SET failed: $reply" }
    } finally {
        $client.Close()
    }
}

function Delete-Token([long]$tokenUid) {
    $client = New-Object Net.Sockets.TcpClient($RedisHost, $RedisPort)
    try {
        $stream = $client.GetStream()
        $bytes = [Text.Encoding]::UTF8.GetBytes((Encode-Redis @('SELECT', [string]$RedisDb)) + (Encode-Redis @('DEL', ('user:token:' + $tokenUid))))
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush()
        Start-Sleep -Milliseconds 120
    } finally {
        $client.Close()
    }
}

function Write-Utf8Json([string]$path, [string]$json) {
    [IO.File]::WriteAllText($path, $json, (New-Object Text.UTF8Encoding($false)))
}

# Returns @{ status = <int>; body = <string> }
function Invoke-Http([string]$url, [string]$jsonFile, [string]$cookie, [string]$extraHeader = '', [string]$method = 'POST') {
    $out = Join-Path $script:tmp 'p6-resp.txt'
    $curlArgs = @('-s', '-o', $out, '-w', '%{http_code}', '-X', $method, '--max-time', '20')
    if ($cookie) { $curlArgs += @('-H', "Cookie: token=$cookie") }
    if ($extraHeader) { $curlArgs += @('-H', $extraHeader) }
    if ($jsonFile) { $curlArgs += @('-H', 'Content-Type: application/json', '--data-binary', "@$jsonFile") }
    $curlArgs += $url
    $status = & curl.exe @curlArgs
    $body = ''
    if (Test-Path $out) { $body = [IO.File]::ReadAllText($out) }
    return @{ status = [int]$status; body = $body }
}

function Test-CompleteFunction([string]$text, [int]$minBraces = 2) {
    $sig = $text -match '(?m)(public|private|protected|static|def|function)[^
]*\('
    $ret = $text -match 'return'
    $ctl = $text -match '(for|while|if|switch|foreach)'
    $open = ([regex]::Matches($text, '\{')).Count
    $close = ([regex]::Matches($text, '\}')).Count
    return ($sig -and $ret -and $ctl -and $open -ge $minBraces -and $close -ge $minBraces)
}

function Get-SseEvents([string]$raw) {
    return @([regex]::Matches($raw, '(?m)^event:\s*(\w+)') | ForEach-Object { $_.Groups[1].Value })
}

# Concatenate every delta.text. The answer is chunked, so a phrase can straddle
# two frames; asserting on the raw SSE body would miss it.
function Get-DeltaText([string]$raw) {
    $texts = @()
    foreach ($line in ($raw -split "`n")) {
        if ($line -match '^data:\s*(\{.*\})\s*$') {
            $obj = $Matches[1] | ConvertFrom-Json
            if ($obj.PSObject.Properties.Name -contains 'text') { $texts += [string]$obj.text }
        }
    }
    return ($texts -join '')
}

function New-ChatBody([string]$mode = 'chat', [int]$level = 1, [string]$message = 'how do I approach two sum', [int]$problemId = 1, [string]$sessionId = '') {
    $file = Join-Path $script:tmp ('p6-chat-' + [guid]::NewGuid().ToString('N') + '.json')
    $payload = @{ problemId = $problemId; mode = $mode; level = $level; message = $message }
    if ($sessionId) { $payload['sessionId'] = $sessionId }
    Write-Utf8Json $file ($payload | ConvertTo-Json -Compress)
    return $file
}

function Invoke-Mysql([string]$sql) {
    $password = $env:OJ_MYSQL_PASSWORD
    if (-not $password) {
        throw 'OJ_MYSQL_PASSWORD is not set: read the password from .runtime/run/judge/application.yml and export it'
    }
    $env:MYSQL_PWD = $password
    return & $MysqlExe '--host=127.0.0.1' '--user=root' '--database=oj' '--batch' '--skip-column-names' -e $sql
}

# A zero-row query must yield '' and not the PowerShell "automation null":
# [string] applied to a function that emits nothing is $null, and .Trim() then
# throws. The session poll hits this whenever the row is not written yet.
function Get-Scalar([string]$sql) {
    return (@(Invoke-Mysql $sql) -join '').Trim()
}

$token = New-TokenFor $Uid 'admin'
Register-Token $token $Uid
if ($Scenario -in @('skill', 'skill-use', 'attribution', 'all')) {
    $nonAdminUid = $Uid + 1
    $nonAdminToken = New-TokenFor $nonAdminUid 'user'
    Register-Token $nonAdminToken $nonAdminUid
    # A second admin is required: the author may never approve their own Skill.
    $admin2Uid = $Uid + 2
    $admin2Token = New-TokenFor $admin2Uid 'admin'
    Register-Token $admin2Token $admin2Uid
}
$body = New-ChatBody

try {
    if ($Scenario -in @('smoke', 'all')) {
        Write-Host '-- smoke'

        $health = Invoke-Http "$AiBase/health" $null $null '' 'GET'
        $healthJson = $health.body | ConvertFrom-Json
        Check 'ai.health200' ($health.status -eq 200) "status=$($health.status)"
        Check 'ai.healthProviderKnown' ($healthJson.provider -in @('fake', 'deepseek')) "provider=$($healthJson.provider)"
        $providerName = [string]$healthJson.provider

        $body = New-ChatBody

        $anon = Invoke-Http "$Gateway/api/ai/chat" $body $null
        Check 'gateway.noCookie401' ($anon.status -eq 401) "status=$($anon.status)"

        # A client-supplied X-Uid must never be trusted: the gateway strips it.
        $forged = Invoke-Http "$Gateway/api/ai/chat" $body $null 'X-Uid: 999'
        Check 'gateway.forgedUidRejected' ($forged.status -eq 401) "status=$($forged.status)"

        $chat = Invoke-Http "$Gateway/api/ai/chat" $body $token
        Check 'chat.http200' ($chat.status -eq 200) "status=$($chat.status)"
        $events = Get-SseEvents $chat.body
        Check 'chat.metaFirst' ($events.Count -gt 0 -and $events[0] -eq 'meta') "events=$($events -join ',')"
        Check 'chat.hasDelta' (($events -contains 'delta')) "events=$($events -join ',')"
        Check 'chat.doneLast' ($events.Count -gt 1 -and $events[-1] -eq 'done') "events=$($events -join ',')"
        Check 'chat.doneStop' ($chat.body -match '"finishReason":\s*"stop"')
        Check 'chat.doneTokensEstimated' ($chat.body -match '"estimated":\s*true')

        $metaLine = ($chat.body -split "`n" | Where-Object { $_ -match '"contextTokens"' } | Select-Object -First 1)
        # skillsUsed must be present as an array; it need not be empty. It is
        # empty only on a DB with no skills, and the P8 skill fixtures seed some
        # for this very uid -- asserting [] made the gate fail on fixture state.
        Check 'chat.metaFields' ($metaLine -and ($metaLine -match '"level":\s*1') -and ($metaLine -match '"skillsUsed":\s*\[')) "meta=$metaLine"
        $metaObj = $metaLine -replace '^data:\s*', '' | ConvertFrom-Json
        Check 'chat.metaPromptTokens' ($metaObj.promptTokens -gt 0) "promptTokens=$($metaObj.promptTokens)"
        Check 'chat.metaContextWithinBudget' ($metaObj.contextTokens -le 1200) "contextTokens=$($metaObj.contextTokens)"
        Check 'chat.metaSummarySourceKnown' ($metaObj.summarySource -in @('RULE', 'LLM')) "source=$($metaObj.summarySource)"

        $metrics = Invoke-Http "$Gateway/api/ai/metrics" $null $token '' 'GET'
        $metricsJson = $metrics.body | ConvertFrom-Json
        Check 'metrics.http200' ($metrics.status -eq 200) "status=$($metrics.status)"
        Check 'metrics.provider' ($metricsJson.provider -eq $providerName) "provider=$($metricsJson.provider)"
        Check 'metrics.requestsCounted' ($metricsJson.requests -ge 1) "requests=$($metricsJson.requests)"
        if ($providerName -eq 'fake') {
            # Fake has no real first token, so the number must stay null rather
            # than become a plausible-looking constant.
            Check 'metrics.ttftUnavailableForFake' ($metricsJson.ttftMs.available -eq $false) "available=$($metricsJson.ttftMs.available)"
        } else {
            Check 'metrics.ttftMeasured' ($metricsJson.ttftMs.available -eq $true) "available=$($metricsJson.ttftMs.available)"
        }

        Check 'metrics.keyStateOnlyPresentOrAbsent' ($metricsJson.key -in @('present', 'absent')) "key=$($metricsJson.key)"
        Check 'metrics.limitsExposed' ($metricsJson.limits.uidPerMin -gt 0 -and $metricsJson.limits.globalPerMin -gt 0) "uid=$($metricsJson.limits.uidPerMin)"

        # Rate limiting: assert the 429 path only when the process runs with the
        # documented default; the benchmark raises the limits on purpose.
        if ([int]$metricsJson.limits.uidPerMin -le 10) {
            $burstUid = $Uid + 40
            $burstToken = New-TokenFor $burstUid 'admin'
            Register-Token $burstToken $burstUid
            try {
                # Fire the burst concurrently. Sequential requests let the token
                # bucket refill while the fake provider streams (one token per
                # 10 s), so the seventh would be accepted whenever a per-chunk
                # delay is configured -- and the browser test needs that delay.
                # HttpClient instead of curl: Start-Process joins -ArgumentList
                # without quoting, which splits "Cookie: token=..." in two.
                Add-Type -AssemblyName System.Net.Http
                # UseCookies=false: the default handler owns a CookieContainer
                # and silently drops a Cookie header set by hand.
                $handler = New-Object System.Net.Http.HttpClientHandler
                $handler.UseCookies = $false
                $client = New-Object System.Net.Http.HttpClient($handler)
                $client.Timeout = [TimeSpan]::FromSeconds(30)
                $burstJson = [IO.File]::ReadAllText($body)
                $tasks = @()
                for ($i = 0; $i -lt 7; $i++) {
                    $request = New-Object System.Net.Http.HttpRequestMessage(
                        [System.Net.Http.HttpMethod]::Post, "$Gateway/api/ai/chat")
                    $request.Headers.Add('Cookie', "token=$burstToken")
                    $request.Content = New-Object System.Net.Http.StringContent(
                        $burstJson, [Text.Encoding]::UTF8, 'application/json')
                    $tasks += $client.SendAsync($request)
                }
                [Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]$tasks)
                $codes = @()
                foreach ($task in $tasks) { $codes += [int]$task.Result.StatusCode }
                $client.Dispose()
                Check 'ratelimit.seventhRequest429' (@($codes | Where-Object { $_ -eq 429 }).Count -ge 1) "codes=$($codes -join ',')"
                Check 'ratelimit.firstSixAccepted' (@($codes | Where-Object { $_ -eq 200 }).Count -eq 6) "codes=$($codes -join ',')"
            } finally {
                Delete-Token $burstUid
            }
        } else {
            Write-Host 'skip  ratelimit.seventhRequest429 (limits raised: P6_RATE_UID_PER_MIN)'
        }

        $metricsAnon = Invoke-Http "$Gateway/api/ai/metrics" $null $null '' 'GET'
        Check 'metrics.noCookie401' ($metricsAnon.status -eq 401) "status=$($metricsAnon.status)"

        $badMode = Join-Path $script:tmp 'p6-bad-mode.json'
        Write-Utf8Json $badMode '{"problemId":1,"mode":"nope","level":1,"message":"x"}'
        $r = Invoke-Http "$Gateway/api/ai/chat" $badMode $token
        Check 'chat.badMode400' ($r.status -eq 400) "status=$($r.status)"

        $badLevel = Join-Path $script:tmp 'p6-bad-level.json'
        Write-Utf8Json $badLevel '{"problemId":1,"mode":"chat","level":4,"message":"x"}'
        $r = Invoke-Http "$Gateway/api/ai/chat" $badLevel $token
        Check 'chat.badLevel400' ($r.status -eq 400) "status=$($r.status)"

        $noProblem = Join-Path $script:tmp 'p6-no-problem.json'
        Write-Utf8Json $noProblem '{"mode":"chat","level":1,"message":"x"}'
        $r = Invoke-Http "$Gateway/api/ai/chat" $noProblem $token
        Check 'chat.missingProblem400' ($r.status -eq 400) "status=$($r.status)"
    }

    if ($Scenario -in @('context', 'all')) {
        Write-Host '-- context'

        # Deterministic fixtures: two Episodes and one WEAK_TAG pattern for the
        # dedicated uid. They are written directly and removed in finally, so a
        # failed run cannot leak rows into the real user's history.
        $ep1 = 9910000001
        $ep2 = 9910000002
        $pat1 = 9910000001
        $cleanup = "DELETE FROM learning_episode WHERE id IN ($ep1,$ep2); DELETE FROM learning_pattern WHERE id=$pat1;"
        Invoke-Mysql $cleanup | Out-Null
        $fixture = @"
INSERT INTO learning_episode (id,uid,problem_id,started_at,ended_at,outcome,attempt_count,ac_count,run_count,solution_views,copy_count,dwell_ms,event_count,summary) VALUES
 ($ep1,$Uid,1,NOW(3) - INTERVAL 2 DAY,NOW(3) - INTERVAL 47 HOUR,'WA',2,0,2,0,0,600000,4,'tried a nested loop and hit the time limit'),
 ($ep2,$Uid,1,NOW(3) - INTERVAL 1 DAY,NOW(3) - INTERVAL 23 HOUR,'AC',3,1,3,0,0,900000,6,'switched to a hash map and passed');
INSERT INTO learning_pattern (id,uid,pattern_type,description,evidence,confidence) VALUES
 ($pat1,$Uid,'WEAK_TAG','weak at arrays and boundaries','{}',0.900);
"@
        try {
            $fixtureOut = Invoke-Mysql $fixture 2>&1
            if ($LASTEXITCODE -ne 0) {
                Write-Host ("fixture failed: " + ($fixtureOut -join '|'))
            }
            $patCount = (Get-Scalar "SELECT COUNT(*) FROM learning_pattern WHERE id=$pat1 AND uid=$Uid;")
            Check 'context.fixturePattern' ($patCount -eq '1') "count=$patCount"

            $chat = Invoke-Http "$Gateway/api/ai/chat" $body $token
            Check 'context.http200' ($chat.status -eq 200) "status=$($chat.status)"
            $metaLine = ($chat.body -split "`n" | Where-Object { $_ -match '"contextTokens"' } | Select-Object -First 1)
            $meta = $metaLine -replace '^data:\s*', '' | ConvertFrom-Json
            Check 'context.tokensPositive' ($meta.contextTokens -gt 0) "contextTokens=$($meta.contextTokens)"
            Check 'context.tokensWithinBudget' ($meta.contextTokens -le 1200) "contextTokens=$($meta.contextTokens)"
            Check 'context.episodesUsed' ($meta.budget.episodesUsed -eq 2) "episodesUsed=$($meta.budget.episodesUsed)"
            Check 'context.patternsUsed' ($meta.budget.patternsUsed -eq 1) "patternsUsed=$($meta.budget.patternsUsed)"
            Check 'context.promptTokensCoversContext' ($meta.promptTokens -ge $meta.contextTokens) "prompt=$($meta.promptTokens) context=$($meta.contextTokens)"
            $answer = Get-DeltaText $chat.body
            Check 'context.weakTagReachedPrompt' ($answer -match 'weak at arrays and boundaries') "answerLen=$($answer.Length)"

            $missing = Join-Path $script:tmp 'p6-missing-problem.json'
            Write-Utf8Json $missing '{"problemId":999999,"mode":"chat","level":1,"message":"x"}'
            $r = Invoke-Http "$Gateway/api/ai/chat" $missing $token
            Check 'context.problemMissing400' ($r.status -eq 400) "status=$($r.status)"

            $turns = @(1..10 | ForEach-Object { '{"role":"user","content":"turn ' + $_ + '"}' })
            $history = '{"problemId":1,"mode":"chat","level":1,"message":"x","history":[' + ($turns -join ',') + ']}'
            $historyFile = Join-Path $script:tmp 'p6-history.json'
            Write-Utf8Json $historyFile $history
            $r = Invoke-Http "$Gateway/api/ai/chat" $historyFile $token
            Check 'context.historyTrimmed' ($r.body -match '"historyTrimmed":\s*true')
        } finally {
            Invoke-Mysql $cleanup | Out-Null
        }
    }

    if ($Scenario -in @('ladder', 'all')) {
        Write-Host '-- ladder'
        $question = 'how should I think about two sum'

        $one = Invoke-Http "$Gateway/api/ai/chat" (New-ChatBody 'chat' 1 $question) $token
        $three = Invoke-Http "$Gateway/api/ai/chat" (New-ChatBody 'chat' 3 $question) $token
        $textOne = Get-DeltaText $one.body
        $textThree = Get-DeltaText $three.body

        Check 'ladder.level1NoCodeFence' (-not ($textOne -match '```')) "text=$($textOne.Substring(0, [Math]::Min(60, $textOne.Length)))"
        Check 'ladder.level3HasCodeBlock' ($textThree -match '```')
        Check 'ladder.level3NotSubmittable' (-not ($textThree -match '(?m)^\s*public\s+class')) 
        Check 'ladder.levelsDiffer' ($textOne -ne $textThree)
        Check 'ladder.level1Status200' ($one.status -eq 200) "status=$($one.status)"
        Check 'ladder.level3Status200' ($three.status -eq 200) "status=$($three.status)"
    }

    if ($Scenario -in @('guard', 'all')) {
        Write-Host '-- guard'
        $fixture = Join-Path (Get-Location) 'benchmark/fixtures/p6-redteam.json'
        $redteam = Get-Content $fixture -Encoding UTF8 -Raw | ConvertFrom-Json
        $answered = 0
        $bad = 0
        foreach ($prompt in $redteam.prompts) {
            $file = New-ChatBody $prompt.mode ([int]$prompt.level) $prompt.message ([int]$redteam.problemId)
            $r = Invoke-Http "$Gateway/api/ai/chat" $file $token
            if ($r.status -ne 200) {
                $bad++
                Write-Host ("redteam {0} status={1}" -f $prompt.id, $r.status)
                continue
            }
            $answered++
            $text = Get-DeltaText $r.body
            $level = [int]$prompt.level
            $violated = $false
            if ($level -eq 1) {
                $violated = $text -match '```'
            } elseif ($level -eq 2) {
                $violated = Test-CompleteFunction $text 2
            } else {
                $violated = Test-CompleteFunction $text 3
            }
            if ($violated) {
                $bad++
                Write-Host ("redteam {0} level={1} violated" -f $prompt.id, $level)
            }
        }
        Check 'guard.redteamAllAnswered' ($answered -eq $redteam.prompts.Count) "answered=$answered of $($redteam.prompts.Count)"
        Check 'guard.redteamCompliant' ($bad -eq 0) "bad=$bad"

        $metrics = (Invoke-Http "$Gateway/api/ai/metrics" $null $token '' 'GET').body | ConvertFrom-Json
        Check 'guard.ladderViolationsZero' ($metrics.guard.ladderViolations -eq 0) "violations=$($metrics.guard.ladderViolations)"
        Check 'guard.leakBlockedZero' ($metrics.guard.leakBlocked -eq 0) "blocked=$($metrics.guard.leakBlocked)"
        Check 'guard.fingerprintsArmed' ($metrics.guard.fingerprintProblems -gt 0) "problems=$($metrics.guard.fingerprintProblems)"
        Check 'guard.fingerprintHashesLoaded' ($metrics.guard.fingerprints -gt 0) "hashes=$($metrics.guard.fingerprints)"
    }

    if ($Scenario -in @('skill', 'all')) {
        Write-Host '-- skill'
        $skillPath = "$Gateway/api/judge/learning/skill"
        $create = Join-Path $script:tmp 'p6-skill-create.json'
        Write-Utf8Json $create ('{"name":"p6 probe ' + [guid]::NewGuid().ToString('N').Substring(0, 8) +
            '","content":"1. read the input\n2. reduce to a hash map\n3. check the boundary","scope":"PERSONAL","tags":["arrays"]}')

        $r = Invoke-Http $skillPath $create $token
        $created = $r.body | ConvertFrom-Json
        $skillId = $created.data
        Check 'skill.createDraft' ($r.status -eq 200 -and $skillId) "status=$($r.status) id=$skillId"

        try {
            $list = (Invoke-Http "$skillPath/list?status=DRAFT" $null $token '' 'GET').body | ConvertFrom-Json
            Check 'skill.listContainsDraft' (@($list.data | ForEach-Object { $_.id }) -contains $skillId)

            # Illegal edge: REVIEW+ is required before ACTIVE. Use the second
            # admin: the author would trip the self-approval check first (403).
            $approve = Join-Path $script:tmp 'p6-skill-approve.json'
            Write-Utf8Json $approve '{"approve":true}'
            $r = Invoke-Http "$skillPath/$skillId/review" $approve $admin2Token
            $bodyR = $r.body | ConvertFrom-Json
            Check 'skill.illegalTransitionRejected' ($bodyR.code -eq 40001) "code=$($bodyR.code)"

            $r = Invoke-Http "$skillPath/$skillId/submit" $null $token
            Check 'skill.submit200' (($r.body | ConvertFrom-Json).data.status -eq 'REVIEW') "status=$($r.status)"

            # A plain user token must not be able to approve, even for their own Skill.
            $r = Invoke-Http "$skillPath/$skillId/review" $approve $nonAdminToken
            $bodyU = $r.body | ConvertFrom-Json
            Check 'skill.nonAdminCannotApprove' ($bodyU.code -eq 403) "code=$($bodyU.code)"

            # spec US14: the author cannot approve their own Skill, even with the admin role.
            $r = Invoke-Http "$skillPath/$skillId/review" $approve $token
            $bodySelf = $r.body | ConvertFrom-Json
            Check 'skill.selfApprovalRejected' ($bodySelf.code -eq 403) "code=$($bodySelf.code)"
            $stillReview = (Get-Scalar "SELECT status FROM learning_skill WHERE id=$skillId;")
            Check 'skill.selfApprovalKeepsReview' ($stillReview -eq 'REVIEW') "status=$stillReview"

            $r = Invoke-Http "$skillPath/$skillId/review" $approve $admin2Token
            Check 'skill.approve200' (($r.body | ConvertFrom-Json).data.status -eq 'ACTIVE') "status=$($r.status)"

            $r = Invoke-Http "$skillPath/$skillId/deprecate" $null $token
            Check 'skill.deprecate200' (($r.body | ConvertFrom-Json).data.status -eq 'DEPRECATED') "status=$($r.status)"

            $audits = (Get-Scalar "SELECT COUNT(*) FROM learning_skill_audit WHERE skill_id=$skillId;")
            Check 'skill.auditRows' ($audits -eq '4') "audits=$audits"
        } finally {
            Invoke-Mysql "DELETE FROM learning_skill_audit WHERE skill_id=$skillId; DELETE FROM learning_skill WHERE id=$skillId;" | Out-Null
        }
    }

    if ($Scenario -in @('skill-extract', 'all')) {
        Write-Host '-- skill-extract'
        $skillPath = "$Gateway/api/judge/learning/skill/extract"
        $goodEpisode = 9920000001
        $viewedEpisode = 9920000002

        $episodeSql = "INSERT INTO learning_episode (id,uid,problem_id,started_at,ended_at,outcome,attempt_count,ac_count,run_count,solution_views,copy_count,dwell_ms,event_count,summary) VALUES " +
            "($goodEpisode,$Uid,1,NOW(3) - INTERVAL 3 HOUR,NOW(3) - INTERVAL 2 HOUR,'AC',2,1,2,0,0,600000,6,'solved with a hash map')," +
            "($viewedEpisode,$Uid,1,NOW(3) - INTERVAL 5 HOUR,NOW(3) - INTERVAL 4 HOUR,'AC',2,1,2,1,0,600000,6,'looked at the solution first');"
        $cleanup = "DELETE FROM learning_skill_audit WHERE skill_id IN (SELECT id FROM learning_skill WHERE source_episode_id IN ($goodEpisode,$viewedEpisode)); " +
            "DELETE FROM learning_skill WHERE source_episode_id IN ($goodEpisode,$viewedEpisode); " +
            "DELETE FROM learning_episode WHERE id IN ($goodEpisode,$viewedEpisode);"

        Invoke-Mysql $cleanup | Out-Null
        try {
            $insertOut = Invoke-Mysql $episodeSql 2>&1
            if ($LASTEXITCODE -ne 0) { Write-Host ("fixture failed: " + ($insertOut -join '|')) }

            $r = Invoke-Http $skillPath $null $token
            $extract = $r.body | ConvertFrom-Json
            $created = @($extract.data.created)
            Check 'extract.createdOne' ($created.Count -eq 1) "created=$($created.Count) status=$($r.status)"
            Check 'extract.skipsViewedSolution' ($created.Count -eq 1) "created=$($created.Count)"

            $drafts = (Invoke-Http "$Gateway/api/judge/learning/skill/list?status=DRAFT" $null $token '' 'GET').body | ConvertFrom-Json
            $draft = @($drafts.data | Where-Object { $_.id -eq $created[0] }) | Select-Object -First 1
            Check 'extract.sourceEpisodeRecorded' ($draft -and $draft.sourceEpisodeId -eq $goodEpisode) "source=$($draft.sourceEpisodeId)"
            Check 'extract.contentWithinLimit' ($draft -and $draft.content.Length -le 500) "len=$($draft.content.Length)"
            $steps = 0
            if ($draft -and $draft.stepsJson) {
                # Assign first: @(pipe).Count counts the pipeline as one object.
                $parsedSteps = $draft.stepsJson | ConvertFrom-Json
                $steps = @($parsedSteps).Count
            }
            Check 'extract.stepsAtLeastThree' ($steps -ge 3) "steps=$steps"
            Check 'extract.recordsModel' ($draft -and $draft.model) "model=$($draft.model)"

            # Same Episode must not produce a second candidate.
            $r = Invoke-Http $skillPath $null $token
            $again = $r.body | ConvertFrom-Json
            Check 'extract.idempotentPerEpisode' (@($again.data.created).Count -eq 0) "created=$(@($again.data.created).Count)"
        } finally {
            Invoke-Mysql $cleanup | Out-Null
        }
    }

    if ($Scenario -in @('skill-use', 'all')) {
        Write-Host '-- skill-use'
        $skillPath = "$Gateway/api/judge/learning/skill"
        $probeName = 'p6 use probe ' + [guid]::NewGuid().ToString('N').Substring(0, 8)
        $otherSkillId = 9930000001
        $cleanupUse = "DELETE FROM learning_skill_usage WHERE skill_id IN (SELECT id FROM learning_skill WHERE name LIKE 'p6 use probe%' OR id = $otherSkillId); " +
            "DELETE FROM learning_skill_audit WHERE skill_id IN (SELECT id FROM learning_skill WHERE name LIKE 'p6 use probe%' OR id = $otherSkillId); " +
            "DELETE FROM learning_skill WHERE name LIKE 'p6 use probe%' OR id = $otherSkillId;"

        Invoke-Mysql $cleanupUse | Out-Null
        $skillId = $null
        try {
            $create = Join-Path $script:tmp 'p6-use-create.json'
            Write-Utf8Json $create ('{"name":"' + $probeName + '","content":"1. draw the state table\n2. fill it row by row\n3. read the answer from the last cell","scope":"PERSONAL","tags":["baseline"]}')
            $r = Invoke-Http $skillPath $create $token
            $skillId = ($r.body | ConvertFrom-Json).data

            $approve = Join-Path $script:tmp 'p6-use-approve.json'
            Write-Utf8Json $approve '{"approve":true}'
            Invoke-Http "$skillPath/$skillId/submit" $null $token | Out-Null
            Invoke-Http "$skillPath/$skillId/review" $approve $admin2Token | Out-Null

            # Another user's PERSONAL Skill must never appear in this user's context.
            # JSON_ARRAY avoids embedding double quotes: PowerShell mangles them
            # when it re-quotes arguments for a native executable.
            $otherSql = 'INSERT INTO learning_skill (id,name,scope,owner_uid,status,content,steps_json,tags,usage_count,success_count,version,created_at,updated_at) ' +
                "VALUES ({0},'p6 use probe other','PERSONAL',{1},'ACTIVE','1. a 2. b 3. c','[]',JSON_ARRAY('baseline'),0,0,1,NOW(3),NOW(3));" -f $otherSkillId, ($Uid + 5)
            Invoke-Mysql $otherSql | Out-Null
            $foreign = (Get-Scalar "SELECT COUNT(*) FROM learning_skill WHERE id=$otherSkillId;")
            Check 'skillUse.otherUserSkillFixture' ($foreign -eq '1') "rows=$foreign"

            $chat = Invoke-Http "$Gateway/api/ai/chat" $body $token
            $metaLine = ($chat.body -split "`n" | Where-Object { $_ -match '"contextTokens"' } | Select-Object -First 1)
            $meta = $metaLine -replace '^data:\s*', '' | ConvertFrom-Json
            $used = @($meta.skillsUsed)
            Check 'skillUse.injected' ($used -contains $probeName) "used=$($used -join ',')"
            Check 'skillUse.otherUsersSkillHidden' ($used.Count -eq 1) "used=$($used -join ',')"

            $answer = Get-DeltaText $chat.body
            Check 'skillUse.answerMentionsSkill' ($answer -match [regex]::Escape($probeName))

            $usage = (Get-Scalar "SELECT usage_count FROM learning_skill WHERE id=$skillId;")
            Check 'skillUse.usageCountedOnce' ($usage -eq '1') "usage=$usage"

            # The attribution trail: which problem, and not yet rewarded.
            $trail = (Get-Scalar "SELECT CONCAT(problem_id, ',', rewarded) FROM learning_skill_usage WHERE skill_id=$skillId;")
            Check 'skillUse.usageTrailRecordsProblem' ($trail -eq '1,0') "trail=$trail"

            Invoke-Http "$skillPath/$skillId/deprecate" $null $token | Out-Null
            $chat = Invoke-Http "$Gateway/api/ai/chat" $body $token
            $metaLine = ($chat.body -split "`n" | Where-Object { $_ -match '"contextTokens"' } | Select-Object -First 1)
            $meta = $metaLine -replace '^data:\s*', '' | ConvertFrom-Json
            Check 'skillUse.deprecatedNotInjected' (@($meta.skillsUsed).Count -eq 0) "used=$(@($meta.skillsUsed) -join ',')"
        } finally {
            Invoke-Mysql $cleanupUse | Out-Null
        }
    }

    if ($Scenario -in @('summary', 'all')) {
        Write-Host '-- summary'
        $summaryUid = $Uid + 9
        $summaryToken = New-TokenFor $summaryUid 'admin'
        Register-Token $summaryToken $summaryUid
        $episode = 9940000001
        $ruleSummary = 'rule summary original'
        $cleanupSummary = "DELETE FROM learning_episode_summary WHERE episode_id=$episode; DELETE FROM learning_episode WHERE id=$episode;"

        Invoke-Mysql $cleanupSummary | Out-Null
        try {
            Invoke-Mysql ("INSERT INTO learning_episode (id,uid,problem_id,started_at,ended_at,outcome,attempt_count,ac_count,run_count,solution_views,copy_count,dwell_ms,event_count,summary) VALUES " +
                "($episode,$summaryUid,1,NOW(3) - INTERVAL 6 HOUR,NOW(3) - INTERVAL 5 HOUR,'AC',2,1,2,0,0,600000,6,'$ruleSummary');") | Out-Null

            $r = Invoke-Http "$Gateway/api/judge/learning/summary/llm?episodeId=$episode" $null $token
            $first = ($r.body | ConvertFrom-Json).data
            Check 'summary.created' ([int]$first.created -ge 1) "created=$($first.created)"

            $untouched = (Get-Scalar "SELECT summary FROM learning_episode WHERE id=$episode;")
            Check 'summary.ruleRowUnchanged' ($untouched -eq $ruleSummary) "summary=$untouched"

            $rows = (Get-Scalar "SELECT COUNT(*) FROM learning_episode_summary WHERE episode_id=$episode AND source='LLM' AND model IS NOT NULL AND prompt_version='summary-v1';")
            Check 'summary.llmRowHasProvenance' ($rows -eq '1') "rows=$rows"

            $r = Invoke-Http "$Gateway/api/judge/learning/summary/llm?episodeId=$episode" $null $token
            $again = ($r.body | ConvertFrom-Json).data
            $rowsAgain = (Get-Scalar "SELECT COUNT(*) FROM learning_episode_summary WHERE episode_id=$episode;")
            Check 'summary.idempotent' ([int]$again.created -eq 0 -and $rowsAgain -eq '1') "created=$($again.created) rows=$rowsAgain"

            $ctx = (Invoke-Http "$Gateway/api/judge/learning/context?problemId=1" $null $summaryToken '' 'GET').body | ConvertFrom-Json
            Check 'summary.contextSourceIsLlm' ($ctx.data.summarySource -eq 'LLM') "source=$($ctx.data.summarySource)"
            $shown = @($ctx.data.episodes)[0].summary
            Check 'summary.contextPrefersLlm' ($shown -and $shown -ne $ruleSummary) "shown=$shown"

            # ... and the SSE frame must carry the same fact, not a hardcoded "RULE".
            $summaryChat = Invoke-Http "$Gateway/api/ai/chat" $body $summaryToken
            $metaLine = ($summaryChat.body -split "`n" | Where-Object { $_ -match '"summarySource"' } | Select-Object -First 1)
            $metaSummary = $metaLine -replace '^data:\s*', '' | ConvertFrom-Json
            Check 'summary.metaSummarySourceLlm' ($metaSummary.summarySource -eq 'LLM') "source=$($metaSummary.summarySource)"
        } finally {
            Invoke-Mysql $cleanupSummary | Out-Null
        }
    }
    if ($Scenario -in @('session', 'all')) {
        Write-Host '-- session'

        # P7 ticket 01: a tutoring session must leave a row in judge's table,
        # otherwise the second-AC rate has no denominator. The row is written on
        # a daemon thread after the stream ends, so the assertions poll briefly.
        $sessionUid = $Uid + 13
        $sessionToken = New-TokenFor $sessionUid 'admin'
        $cleanupSession = "DELETE FROM ai_tutoring_session WHERE uid=$sessionUid;"

        try {
            Register-Token $sessionToken $sessionUid
            Invoke-Mysql $cleanupSession | Out-Null
            $sid = 'p7-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
            $sessionBody = New-ChatBody -level 2 -sessionId $sid
            $sessionChat = Invoke-Http "$Gateway/api/ai/chat" $sessionBody $sessionToken
            Check 'session.chat200' ($sessionChat.status -eq 200) "status=$($sessionChat.status)"
            $sessionMetaLine = ($sessionChat.body -split "`n" | Where-Object { $_ -match '"searchUsed"' } | Select-Object -First 1)
            $sessionMeta = $sessionMetaLine -replace '^data:\s*', '' | ConvertFrom-Json
            $searchFlag = if ($sessionMeta.searchUsed) { '1' } else { '0' }

            # finish_reason | problem_id | level | session_id | tokens? | search_used | duration_ok
            # duration catches a zone skew or a constant timestamp; ordering alone is
            # already enforced by judge, so it would be a tautology here.
            $probe = "SELECT CONCAT(finish_reason, '|', problem_id, '|', level, '|', IFNULL(session_id, '-'), '|', IF(prompt_tokens > 0, '1', '0'), '|', IF(search_used = 0, '0', '1'), '|', IF(TIMESTAMPDIFF(SECOND, started_at, finished_at) BETWEEN 0 AND 60, '1', '0')) FROM ai_tutoring_session WHERE uid=$sessionUid;"
            $row = ''
            for ($i = 0; $i -lt 30; $i++) {
                $row = (Get-Scalar $probe)
                if ($row) { break }
                Start-Sleep -Milliseconds 100
            }
            Check 'session.rowWritten' ($row -ne '') "row=$row"
            Check 'session.reasonStop' ($row -eq "stop|1|2|$sid|1|$searchFlag|1") "row=$row"

            $rows = (Get-Scalar "SELECT COUNT(*) FROM ai_tutoring_session WHERE uid=$sessionUid;")
            Check 'session.oneRowPerCall' ($rows -eq '1') "rows=$rows"

            # The callback is written straight to judge, so the gateway must not
            # expose it: otherwise any logged-in user could forge their own rows.
            $forged = Invoke-Http "$Gateway/api/judge/learning/ai/session" $sessionBody $sessionToken
            Check 'session.gatewayForbidsCallback' ($forged.status -eq 404) "status=$($forged.status)"

            # Belt and braces: bypass the gateway entirely and hit judge on 8012.
            # Without the shared key this must be refused, otherwise the gateway
            # block only hides the door instead of locking it. judge answers with
            # the project-wide envelope, so the refusal can be a 403 status or a
            # 200 carrying code=403.
            $direct = Invoke-Http "$JudgeDirect/learning/ai/session" $sessionBody $sessionToken
            $directCode = 0
            try { $directCode = [int](($direct.body | ConvertFrom-Json).code) } catch { $directCode = 0 }
            $refused = ($direct.status -eq 403) -or ($directCode -eq 403)
            Check 'session.judgeRejectsWithoutKey' $refused "status=$($direct.status) code=$directCode"

            $afterForge = (Get-Scalar "SELECT COUNT(*) FROM ai_tutoring_session WHERE uid=$sessionUid;")
            Check 'session.forgeryWritesNothing' ($afterForge -eq '1') "rows=$afterForge"

        } finally {
            Invoke-Mysql $cleanupSession | Out-Null
            Delete-Token $sessionUid
        }
    }
    if ($Scenario -in @('length')) {
        Write-Host '-- length'

        # Ticket 05, second half. Requires an AI process started with
        # P6_PROVIDER=deepseek and a tiny P6_MAX_TOKENS; deliberately not part of
        # `all`, because the default test environment runs the fake provider and
        # the fake never runs out of budget.
        $lengthUid = $Uid + 15
        $lengthToken = New-TokenFor $lengthUid 'admin'
        $cleanupLength = "DELETE FROM ai_tutoring_session WHERE uid=$lengthUid;"
        try {
            Register-Token $lengthToken $lengthUid
            Invoke-Mysql $cleanupLength | Out-Null

            $health = (Invoke-Http "$AiBase/health" $null $null '' 'GET').body | ConvertFrom-Json
            Check 'length.providerIsDeepseek' ($health.provider -eq 'deepseek') "provider=$($health.provider)"

            $longBody = New-ChatBody -level 1 -message 'Please list at least ten different approaches to this problem and argue for each one in detail.'
            $chat = Invoke-Http "$Gateway/api/ai/chat" $longBody $lengthToken
            Check 'length.chat200' ($chat.status -eq 200) "status=$($chat.status)"
            $doneLine = ($chat.body -split "`n" | Where-Object { $_ -match '"finishReason"' } | Select-Object -Last 1)
            $done = $doneLine -replace '^data:\s*', '' | ConvertFrom-Json
            Check 'length.sseFinishReason' ($done.finishReason -eq 'length') "finishReason=$($done.finishReason)"

            $row = ''
            for ($i = 0; $i -lt 40; $i++) {
                $row = Get-Scalar "SELECT finish_reason FROM ai_tutoring_session WHERE uid=$lengthUid;"
                if ($row) { break }
                Start-Sleep -Milliseconds 100
            }
            Check 'length.sessionRow' ($row -eq 'length') "row=$row"
        } finally {
            Invoke-Mysql $cleanupLength | Out-Null
            Delete-Token $lengthUid
        }
    }
    if ($Scenario -in @('attribution', 'all')) {
        Write-Host '-- attribution'

        # Ticket 03: a real AC must move learning_skill.success_count. The Skill is
        # injected by a real /ai/chat, and the AC comes from a real judge run -- no
        # direct UPDATE of success_count anywhere in this scenario.
        $attrUid = $Uid + 16
        $attrToken = New-TokenFor $attrUid 'admin'
        $probeName = 'p7 attribution probe ' + [guid]::NewGuid().ToString('N').Substring(0, 8)
        $acBody = Join-Path (Get-Location) 'benchmark/fixtures/p7-ac-submit.json'
        $cleanupAttr = "DELETE FROM learning_event WHERE uid=$attrUid; " +
            "DELETE FROM learning_episode_summary WHERE episode_id IN (SELECT id FROM learning_episode WHERE uid=$attrUid); " +
            "DELETE FROM learning_episode WHERE uid=$attrUid; " +
            "DELETE FROM learning_skill_usage WHERE uid=$attrUid; " +
            "DELETE FROM learning_skill_audit WHERE skill_id IN (SELECT id FROM learning_skill WHERE name LIKE 'p7 attribution probe%'); " +
            "DELETE FROM learning_skill WHERE name LIKE 'p7 attribution probe%'; " +
            "DELETE FROM ai_tutoring_session WHERE uid=$attrUid; " +
            "DELETE FROM problem_submit WHERE user_id=$attrUid; " +
            "DELETE FROM user WHERE uid=$attrUid;"

        Invoke-Mysql $cleanupAttr | Out-Null
        $skillId = $null
        try {
            # judge asks the user service for this account's secret before running
            # anything (JudgeWorker), so a synthetic uid needs a real user row.
            $secretSeed = 'p7' + ('{0:D38}' -f $attrUid)
            Invoke-Mysql ("INSERT INTO user (uid, username, email, user_role, secret_id, secret_key) VALUES (" +
                "$attrUid, 'p7-attr-$attrUid', 'p7-attr-$attrUid@example.invalid', 'user', '" + $secretSeed + "', '" + $secretSeed + "');") | Out-Null
            Register-Token $attrToken $attrUid

            $create = Join-Path $script:tmp 'p7-attr-create.json'
            Write-Utf8Json $create ('{"name":"' + $probeName + '","content":"1. check the boundary\n2. trace one example\n3. re-run the failing case","scope":"PERSONAL","tags":["array"]}')
            $r = Invoke-Http "$Gateway/api/judge/learning/skill" $create $attrToken
            $skillId = ($r.body | ConvertFrom-Json).data
            Check 'attribution.skillCreated' ($null -ne $skillId) "status=$($r.status) body=$($r.body)"

            $approve = Join-Path $script:tmp 'p7-attr-approve.json'
            Write-Utf8Json $approve '{"approve":true}'
            Invoke-Http "$Gateway/api/judge/learning/skill/$skillId/submit" $null $attrToken | Out-Null
            Invoke-Http "$Gateway/api/judge/learning/skill/$skillId/review" $approve $admin2Token | Out-Null

            # 1) Inject it through the real tutoring path.
            $chat = Invoke-Http "$Gateway/api/ai/chat" $body $attrToken
            $metaLine = ($chat.body -split "`n" | Where-Object { $_ -match '"skillsUsed"' } | Select-Object -First 1)
            $meta = $metaLine -replace '^data:\s*', '' | ConvertFrom-Json
            Check 'attribution.skillInjected' (@($meta.skillsUsed) -contains $probeName) "used=$(@($meta.skillsUsed) -join ',')"

            $pending = Get-Scalar "SELECT COUNT(*) FROM learning_skill_usage WHERE uid=$attrUid AND skill_id=$skillId AND rewarded=0;"
            Check 'attribution.usagePending' ($pending -eq '1') "rows=$pending"

            # 2) A real AC on the same problem.
            $submit = Invoke-Http "$Gateway/api/judge/submit" $acBody $attrToken
            $submitId = [regex]::Match($submit.body, '"id"\s*:\s*"?(\d{10,})"?').Groups[1].Value
            Check 'attribution.submitAccepted' ($submitId -ne '') "status=$($submit.status) body=$($submit.body)"
            for ($i = 0; $i -lt 240; $i++) {
                $statusBody = (Invoke-Http "$Gateway/api/judge/submit/$submitId/status" $null $attrToken '' 'GET').body
                if ($statusBody -match '"status"\s*:\s*[23]') { break }
                Start-Sleep -Milliseconds 500
            }
            $acEvents = Get-Scalar "SELECT COUNT(*) FROM learning_event WHERE uid=$attrUid AND event_type='ac' AND submit_id=$submitId;"
            Check 'attribution.realAcRecorded' ($acEvents -eq '1') "acEvents=$acEvents"
            $judgeInfo = Get-Scalar "SELECT judge_info FROM problem_submit WHERE id=$submitId;"
            Check 'attribution.judgeSaysAccepted' ($judgeInfo -match '"status"\s*:\s*0') "judgeInfo=$judgeInfo"

            # 3) The attribution listener is async: poll, do not sleep once and hope.
            $sc = ''
            for ($i = 0; $i -lt 60; $i++) {
                $sc = Get-Scalar "SELECT CONCAT(success_count, '|', (SELECT rewarded FROM learning_skill_usage WHERE uid=$attrUid AND skill_id=$skillId ORDER BY used_at DESC LIMIT 1)) FROM learning_skill WHERE id=$skillId;"
                if ($sc -eq '1|1') { break }
                Start-Sleep -Milliseconds 250
            }
            Check 'attribution.successCountedOnce' ($sc -eq '1|1') "count|rewarded=$sc"

            # 4) A second AC must not add again: the usage row was already claimed.
            $again = Invoke-Http "$Gateway/api/judge/submit" $acBody $attrToken
            $againId = [regex]::Match($again.body, '"id"\s*:\s*"?(\d{10,})"?').Groups[1].Value
            for ($i = 0; $i -lt 240; $i++) {
                $statusBody = (Invoke-Http "$Gateway/api/judge/submit/$againId/status" $null $attrToken '' 'GET').body
                if ($statusBody -match '"status"\s*:\s*[23]') { break }
                Start-Sleep -Milliseconds 500
            }
            Start-Sleep -Seconds 2
            $sc = Get-Scalar "SELECT success_count FROM learning_skill WHERE id=$skillId;"
            Check 'attribution.repeatAcNoDoubleCount' ($sc -eq '1') "success=$sc"

            # 5) An injection older than the 10-minute window must not be rewarded.
            Invoke-Http "$Gateway/api/ai/chat" $body $attrToken | Out-Null
            $usageId = Get-Scalar "SELECT id FROM learning_skill_usage WHERE uid=$attrUid AND skill_id=$skillId AND rewarded=0 ORDER BY used_at DESC LIMIT 1;"
            Check 'attribution.freshUsageRow' ($usageId -ne '') "id=$usageId"
            Invoke-Mysql "UPDATE learning_skill_usage SET used_at = NOW(3) - INTERVAL 11 MINUTE WHERE id=$usageId;" | Out-Null

            $stale = Invoke-Http "$Gateway/api/judge/submit" $acBody $attrToken
            $staleId = [regex]::Match($stale.body, '"id"\s*:\s*"?(\d{10,})"?').Groups[1].Value
            for ($i = 0; $i -lt 240; $i++) {
                $statusBody = (Invoke-Http "$Gateway/api/judge/submit/$staleId/status" $null $attrToken '' 'GET').body
                if ($statusBody -match '"status"\s*:\s*[23]') { break }
                Start-Sleep -Milliseconds 500
            }
            Start-Sleep -Seconds 2
            $sc = Get-Scalar "SELECT success_count FROM learning_skill WHERE id=$skillId;"
            $rewarded = Get-Scalar "SELECT rewarded FROM learning_skill_usage WHERE id=$usageId;"
            Check 'attribution.staleInjectionNotCounted' ($sc -eq '1' -and $rewarded -eq '0') "success=$sc rewarded=$rewarded"
        } finally {
            Invoke-Mysql $cleanupAttr | Out-Null
            Delete-Token $attrUid
        }
    }
    if ($Scenario -in @('related', 'all')) {
        Write-Host '-- related'

        # P7 ticket 06: the related-problems section is a bypass feature. Both states are asserted:
        # search up => the section reaches the prompt and the session row says so;
        # embedding sidecar down => the stream is still 200 with no section.
        $relatedUid = $Uid + 14
        $relatedToken = New-TokenFor $relatedUid 'admin'
        $cleanupRelated = "DELETE FROM ai_tutoring_session WHERE uid=$relatedUid;"
        $relatedProbe = "SELECT CONCAT(finish_reason, '|', IF(search_used = 0, '0', '1')) FROM ai_tutoring_session WHERE uid=$relatedUid ORDER BY id DESC LIMIT 1;"
        # The marker is Chinese; keep it in a UTF-8 fixture so this script stays ASCII.
        $relatedFixture = [IO.File]::ReadAllText((Join-Path (Get-Location) 'benchmark/fixtures/p7-related.json'), [Text.Encoding]::UTF8) | ConvertFrom-Json
        $marker = [string]$relatedFixture.answerMarker

        try {
            Register-Token $relatedToken $relatedUid
            Invoke-Mysql $cleanupRelated | Out-Null

            $chat = Invoke-Http "$Gateway/api/ai/chat" $body $relatedToken
            Check 'related.chat200' ($chat.status -eq 200) "status=$($chat.status)"
            $metaLine = ($chat.body -split "`n" | Where-Object { $_ -match '"searchUsed"' } | Select-Object -First 1)
            $meta = $metaLine -replace '^data:\s*', '' | ConvertFrom-Json
            Check 'related.metaSearchUsed' ($meta.searchUsed -eq $true) "searchUsed=$($meta.searchUsed)"
            $answer = Get-DeltaText $chat.body
            Check 'related.sectionReachedPrompt' ($answer -match [regex]::Escape($marker)) "answer=$($answer -replace "`n", ' / ')"

            $row = ''
            for ($i = 0; $i -lt 40; $i++) {
                $row = Get-Scalar $relatedProbe
                if ($row) { break }
                Start-Sleep -Milliseconds 100
            }
            Check 'related.sessionRowSearchUsed' ($row -eq 'stop|1') "row=$row"

            # Take the sidecar away. hybrid is the only path that needs it, so this
            # is the exact failure the contract says must degrade silently.
            $embed = Get-NetTCPConnection -LocalPort 8025 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
            $stopped = $false
            if ($embed) {
                Stop-Process -Id $embed.OwningProcess -Force
                for ($i = 0; $i -lt 50; $i++) {
                    $still = Get-NetTCPConnection -LocalPort 8025 -State Listen -ErrorAction SilentlyContinue
                    if (-not $still) { $stopped = $true; break }
                    Start-Sleep -Milliseconds 200
                }
            }
            Check 'related.sidecarStoppedForProbe' ((-not $embed) -or $stopped) "stopped=$stopped"

            $degraded = Invoke-Http "$Gateway/api/ai/chat" $body $relatedToken
            Check 'related.degradedChat200' ($degraded.status -eq 200) "status=$($degraded.status)"
            $metaLine = ($degraded.body -split "`n" | Where-Object { $_ -match '"searchUsed"' } | Select-Object -First 1)
            $meta = $metaLine -replace '^data:\s*', '' | ConvertFrom-Json
            Check 'related.degradedSearchUsedFalse' ($meta.searchUsed -eq $false) "searchUsed=$($meta.searchUsed)"
            $answer = Get-DeltaText $degraded.body
            Check 'related.degradedHasNoSection' (-not ($answer -match [regex]::Escape($marker))) "answer=$($answer -replace "`n", ' / ')"

            $row = ''
            for ($i = 0; $i -lt 40; $i++) {
                $row = Get-Scalar $relatedProbe
                if ($row -eq 'stop|0') { break }
                Start-Sleep -Milliseconds 100
            }
            Check 'related.degradedRowSearchUsedFalse' ($row -eq 'stop|0') "row=$row"
        } finally {
            Invoke-Mysql $cleanupRelated | Out-Null
            Delete-Token $relatedUid
            # Put the sidecar back: later scenarios and the next run need it.
            if ($embed -and $stopped) {
                Start-Process -FilePath $PythonExe -ArgumentList 'ai_service\embed_service.py' -WorkingDirectory (Get-Location) -WindowStyle Hidden
                $back = $false
                for ($i = 0; $i -lt 120; $i++) {
                    try {
                        $probe = Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8025/health' -TimeoutSec 2
                        if ($probe.StatusCode -eq 200) { $back = $true; break }
                    } catch { }
                    Start-Sleep -Milliseconds 500
                }
                Write-Host ("sidecar restarted: " + $back)
            }
        }
    }
    if ($Scenario -in @('ab', 'all')) {
        Write-Host '-- ab'

        # Real problem ids: the sessions go through the real /ai/chat pipeline.
        $ids = @(Invoke-Mysql 'SELECT id FROM problem ORDER BY id LIMIT 8;')
        $ids = @($ids | Where-Object { $_ -match '^\d+$' })
        if ($ids.Count -ne 8) { throw "ab needs 8 problems in the database, found $($ids.Count)" }
        $problems = ($ids -join ',')

        $personas = @('OFF_BY_ONE', 'TLE', 'SYNTAX')
        $weak = @('weak at boundary checks',
                  'repeatedly hits the time limit; the complexity is the bottleneck',
                  'frequent compile errors; check types and braces first')
        $tokensA = @()
        $tokensB = @()
        $patternIds = @()
        $abUids = @()
        for ($i = 0; $i -lt 3; $i++) {
            # 100-112 is the uid segment reserved for P7's 12 single-person
            # sessions; the A/B arms live at 300-312 so the two never mix.
            $aUid = $Uid + 300 + $i
            $bUid = $Uid + 310 + $i
            $abUids += $aUid
            $abUids += $bUid
            $ta = New-TokenFor $aUid 'admin'
            $tb = New-TokenFor $bUid 'admin'
            Register-Token $ta $aUid
            Register-Token $tb $bUid
            $tokensA += $ta
            $tokensB += $tb
            # Only arm B has a Context, and it is the same shape the service reads.
            $patId = $Uid + 120 + $i
            $patternIds += $patId
            Invoke-Mysql ("DELETE FROM learning_pattern WHERE id=$patId; INSERT INTO learning_pattern (id,uid,pattern_type,description,evidence,confidence) VALUES ($patId," +
                $bUid + ",'WEAK_TAG','" + $weak[$i] + "','{}',0.900);") | Out-Null
        }

        # A/B calls are real /ai/chat requests, so they leave session rows too.
        # Clear them at both ends so a failed run cannot poison the next one.
        $abCleanup = "DELETE FROM ai_tutoring_session WHERE uid IN (" + ($abUids -join ',') + ");"
        Invoke-Mysql $abCleanup | Out-Null

        $before = (Get-Scalar 'SELECT COUNT(*) FROM problem_submit;')
        $callsBefore = (Get-Scalar 'SELECT COUNT(*) FROM problem_submit WHERE user_id BETWEEN 9900000300 AND 9900000313;')

        $abHealth = (Invoke-Http "$AiBase/health" $null $null '' 'GET').body | ConvertFrom-Json
        $abProvider = [string]$abHealth.provider
        # One file per provider: a fake report and a real-key report must never be
        # merged into a single table (ticket 02).
        $reportFile = Join-Path (Get-Location) ('benchmark/results/p6-ab-' + $abProvider + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
        $json = & $PythonExe 'benchmark/p6_ab.py' '--gateway' $Gateway '--tokens-a' ($tokensA -join ',') '--tokens-b' ($tokensB -join ',') '--problems' $problems '--json' '--out' $reportFile
        if ($LASTEXITCODE -ne 0) { Write-Host ("ab harness failed: " + ($json -join '|')) }
        $report = ($json -join '') | ConvertFrom-Json

        Check 'ab.syntheticFlag' ($report.synthetic -eq $true)
        Check 'ab.labelSaysSynthetic' ($report.label -match 'synthetic')
        Check 'ab.judgedByRule' ($report.judgedBy -eq 'rule') "judgedBy=$($report.judgedBy)"
        Check 'ab.runsThroughChatPipeline' ($report.pipeline -match '/ai/chat') "pipeline=$($report.pipeline)"
        Check 'ab.sessionsPerArm' ($report.sessionsPerArm -eq 24) "sessions=$($report.sessionsPerArm)"
        Check 'ab.allSessionsAnswered' ($report.calls -eq 48) "calls=$($report.calls)"
        Check 'ab.providerReported' (@($report.providers).Count -ge 1) "providers=$(@($report.providers) -join ',')"
        Check 'ab.contextImprovesSecondAttempt' ($report.arms.B_with_context.secondAttemptAcRate -gt $report.arms.A_no_context.secondAttemptAcRate) "A=$($report.arms.A_no_context.secondAttemptAcRate) B=$($report.arms.B_with_context.secondAttemptAcRate)"
        Check 'ab.reportWritten' (Test-Path $reportFile) "path=$reportFile"
        Check 'ab.providerMatchesHealth' (@($report.providers) -contains $abProvider) "providers=$(@($report.providers) -join ',') health=$abProvider"
        if ($abProvider -eq 'fake') {
            # A zero would look like a measurement; the contract says null.
            Check 'ab.fakeHasNoTtft' ($null -eq $report.ttft) "ttft=$($report.ttft)"
            Check 'ab.fakeHasNoTokens' ($null -eq $report.tokens) "tokens=$($report.tokens)"
        } else {
            Check 'ab.realKeyHasTtft' ($null -ne $report.ttft -and [int]$report.ttft.samples -gt 0) "ttft=$($report.ttft)"
            Check 'ab.realKeyHasTokens' ($null -ne $report.tokens -and [int]$report.tokens.total -gt 0) "tokens=$($report.tokens)"
        }

        $after = (Get-Scalar 'SELECT COUNT(*) FROM problem_submit;')
        $callsAfter = (Get-Scalar 'SELECT COUNT(*) FROM problem_submit WHERE user_id BETWEEN 9900000300 AND 9900000313;')
        Check 'ab.noRealSubmits' ($before -eq $after -and $callsBefore -eq $callsAfter) "before=$before after=$after"

        foreach ($patId in $patternIds) { Invoke-Mysql "DELETE FROM learning_pattern WHERE id=$patId;" | Out-Null }
        Invoke-Mysql $abCleanup | Out-Null
        foreach ($abUid in $abUids) { Delete-Token $abUid }
    }
} finally {
    Delete-Token $Uid
    if ($nonAdminToken) { Delete-Token ($Uid + 1) }
    if ($admin2Token) { Delete-Token ($Uid + 2) }
    if ($summaryToken) { Delete-Token ($Uid + 9) }
    Write-Host ("assertions=$($script:assertions) fail=$($script:failures)")
    if ($script:failures -gt 0) { exit 1 }
}
