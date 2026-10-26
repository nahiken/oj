# P5 search evidence script (ticket 04: index -> BM25 -> guards).
#
# ASCII only on purpose (see docs/RELIABILITY.md): the repo path contains CJK.
# Run:  powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\benchmark\run-p5.ps1 -Scenario all
#
# It mints a short-lived admin token (HS256, same secret as the user service) and
# registers it in Redis db 12, so no manual login is needed. The secret is read
# from OJ_JWT_SECRET -- never hard-code it here (docs/SECURITY.md).
param(
    [ValidateSet('index', 'bm25', 'guard', 'sync', 'watermark', 'reindex', 'eval', 'latency', 'compare', 'cleanup', 'skill-eval', 'skill-promote', 'skill-cleanup', 'all')]
    [string]$Scenario = 'all',
    [string]$BaseUrl = 'http://127.0.0.1:8012/judge',
    [string]$EsUrl = 'http://127.0.0.1:9200',
    [string]$Index = 'oj_problem_v1',
    [string]$Uid = '1900000000000000001',
    [string]$JwtSecret = '',
    [string]$PythonExe = 'python',
    [string]$MysqlExe = 'mysql',
    [int]$ExpectedDocs = 523,
    [string]$EnQuery = 'game 388535 easy version',
    [int]$EnExpectedId = 0,
    [int]$ZhExpectedId = 0,
    [ValidateSet('bm25', 'dense', 'hybrid')]
    [string]$Mode = 'bm25'
)

$ErrorActionPreference = 'Stop'
if (-not $JwtSecret) { $JwtSecret = $env:OJ_JWT_SECRET }
if (-not $JwtSecret) {
    throw 'OJ_JWT_SECRET is not set: read secret-key from .runtime/run/judge/application.yml and export it (docs/SECURITY.md)'
}
# Child scripts (-Scenario compare) re-read the secret from the environment.
$env:OJ_JWT_SECRET = $JwtSecret
$script:assertions = 0
$script:failures = 0
$script:tmp = Join-Path (Get-Location) '.runtime\tmp'
New-Item -ItemType Directory -Force $script:tmp | Out-Null

# Expected values live in a fixture so the script has no hand-written magic numbers.
$script:expectedFile = Join-Path (Get-Location) 'benchmark/fixtures/p5-expected.json'
if (Test-Path $script:expectedFile) {
    $script:expected = Get-Content $script:expectedFile -Encoding UTF8 -Raw | ConvertFrom-Json
    if (-not $PSBoundParameters.ContainsKey('ExpectedDocs')) { $ExpectedDocs = [int]$script:expected.documents }
    if (-not $PSBoundParameters.ContainsKey('EnExpectedId')) { $EnExpectedId = [int]$script:expected.enExpectedId }
    if (-not $PSBoundParameters.ContainsKey('ZhExpectedId')) { $ZhExpectedId = [int]$script:expected.zhExpectedId }
} else {
    $script:expected = $null
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

function New-AdminToken {
    $header = To-B64Url([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
    $payloadJson = '{"uid":' + $Uid + ',"userRole":"admin"}'
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

# One connection: SELECT 12 then SET, otherwise the token lands in db 0.
function Register-Token([string]$token) {
    $client = New-Object Net.Sockets.TcpClient('127.0.0.1', 6379)
    try {
        $stream = $client.GetStream()
        $bytes = [Text.Encoding]::UTF8.GetBytes((Encode-Redis @('SELECT', '12')) + (Encode-Redis @('SET', ('user:token:' + $Uid), $token)))
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

function Write-Utf8Json([string]$path, [string]$json) {
    [IO.File]::WriteAllText($path, $json, (New-Object Text.UTF8Encoding($false)))
}

# Returns @{ status = <int>; body = <string> }
function Invoke-Search([string]$path, [string]$jsonFile, [string]$cookie, [string]$method = 'POST') {
    $out = Join-Path $script:tmp 'p5-resp.json'
    $curlArgs = @('-s', '-o', $out, '-w', '%{http_code}', '-X', $method)
    if ($cookie) { $curlArgs += @('-H', "Cookie: token=$cookie") }
    if ($jsonFile) { $curlArgs += @('-H', 'Content-Type: application/json', '--data-binary', "@$jsonFile") }
    $curlArgs += ($BaseUrl + $path)
    $status = & curl.exe @curlArgs
    $body = ''
    if (Test-Path $out) {
        $body = [IO.File]::ReadAllText($out, [Text.Encoding]::UTF8)
        Remove-Item $out -Force
    }
    return @{ status = [int]$status; body = $body }
}

function Wait-Until([scriptblock]$probe, [int]$timeoutSec = 20) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (& $probe) { return $true }
        Start-Sleep -Milliseconds 400
    }
    return $false
}

function Get-EsJson([string]$path) {
    $raw = & curl.exe -s ($EsUrl + $path)
    return ([string]::Join('', $raw) | ConvertFrom-Json)
}

function Invoke-Mysql([string]$sql) {
    $password = $env:OJ_MYSQL_PASSWORD
    if (-not $password) {
        throw 'OJ_MYSQL_PASSWORD is not set: read the password from .runtime/run/judge/application.yml and export it'
    }
    $env:MYSQL_PWD = $password
    return & $MysqlExe '--host=127.0.0.1' '--user=root' '--database=oj' '--batch' '--skip-column-names' -e $sql
}

Write-Host "== P5 search evidence: scenario=$Scenario"
$token = New-AdminToken
Register-Token $token

$enFile = Join-Path $script:tmp 'p5-q-en.json'
Write-Utf8Json $enFile ('{"query":"' + $EnQuery + '","mode":"bm25","topK":5}')

if ($Scenario -in @('index', 'all')) {
    Write-Host '-- index'
    $r = Invoke-Search '/search/admin/reindex' $null $token
    $body = $r.body | ConvertFrom-Json
    Check 'index.http200' ($r.status -eq 200) "status=$($r.status)"
    Check 'index.documents' ($body.data.documents -eq $ExpectedDocs) "documents=$($body.data.documents)"
    Check 'index.indexedAll' ($body.data.indexed -eq $body.data.documents) "indexed=$($body.data.indexed)"
    Check 'index.noFailures' ($body.data.failed -eq 0 -and $body.data.conflict -eq 0) "failed=$($body.data.failed) conflict=$($body.data.conflict)"

    $count = Get-EsJson "/$Index/_count"
    Check 'index.esCount' ($count.count -eq $ExpectedDocs) "es=$($count.count)"
}

if ($Scenario -in @('bm25', 'all')) {
    Write-Host '-- bm25'
    $r = Invoke-Search '/search/query' $enFile $token
    $body = $r.body | ConvertFrom-Json
    Check 'bm25.http200' ($r.status -eq 200) "status=$($r.status)"
    Check 'bm25.top1' ($body.data.hits[0].problemId -eq $EnExpectedId) "top1=$($body.data.hits[0].problemId)"
    Check 'bm25.rankIsOne' ($body.data.hits[0].bm25Rank -eq 1)
    Check 'bm25.denseRankNull' ($null -eq $body.data.hits[0].denseRank)
    Check 'bm25.snippetHighlighted' ($body.data.hits[0].snippet -match '<em>')
    Check 'bm25.noContentField' (-not ($r.body -match '"content"'))
    Check 'bm25.noAnswerField' (-not ($r.body -match '"answer"'))
    Check 'bm25.noJudgeCaseField' (-not ($r.body -match 'judgeCase'))
    Check 'bm25.esMsPresent' ($body.data.esMs -ge 0)

    # Chinese lane: smartcn must split a CJK query into words, otherwise only
    # exact-title matches would survive.
    $zhFile = Join-Path $script:tmp 'p5-q-zh.json'
    $zhQuery = [string]([char]0x4E24) + [char]0x6570 + [char]0x4E4B + [char]0x548C
    Write-Utf8Json $zhFile ('{"query":"' + $zhQuery + '","mode":"bm25","topK":5,"lang":"zh"}')
    $r = Invoke-Search '/search/query' $zhFile $token
    $body = $r.body | ConvertFrom-Json
    Check 'bm25.zhHttp200' ($r.status -eq 200) "status=$($r.status)"
    Check 'bm25.zhTop1' ($body.data.hits[0].problemId -eq $ZhExpectedId) "top1=$($body.data.hits[0].problemId)"
    Check 'bm25.zhTotal' ($body.data.total -ge 2) "total=$($body.data.total)"
}

if ($Scenario -in @('guard', 'all')) {
    Write-Host '-- guard'
    # Structural proof: the index mapping and every indexed _source carry the
    # whitelist only -- there is no field that could hold an answer.
    $mapping = & curl.exe -s "$EsUrl/$Index/_mapping"
    $mappingText = [string]::Join('', $mapping)
    Check 'guard.mappingHasNoAnswer' (-not ($mappingText -match '"answer"'))
    Check 'guard.mappingHasNoJudgeCase' (-not ($mappingText -match 'judgeCase'))
    Check 'guard.mappingHasNoJudgeConfig' (-not ($mappingText -match 'judgeConfig'))

    $source = & curl.exe -s "$EsUrl/$Index/_search?size=1"
    $sourceText = [string]::Join('', $source)
    Check 'guard.docHasNoAnswer' (-not ($sourceText -match '"answer"'))
    Check 'guard.docHasNoJudgeCase' (-not ($sourceText -match 'judgeCase'))

    # Behavioural proof with a positive control: insert a probe problem whose
    # answer is a unique token, reindex, then search for it. If the content token
    # is retrievable but the answer token is not, the exclusion is real rather
    # than a vacuous "unknown word returns nothing".
    $probeId = 999001
    $probeContent = 'probecontentzz'
    $probeAnswer = 'uniqueprobeanswerzz'
    $insertSql = "INSERT INTO problem (id,title,content,difficulty,tags,answer,submit_num,accepted_num,judge_case,judge_config,thumb_num,favour_num,user_id,create_time,update_time,is_delete,lang,source) VALUES ($probeId,'p5 guard probe','This statement contains only the token $probeContent.','EASY','[]','$probeAnswer','0','0','[]','{}','0','0','1',NOW(3),NOW(3),0,'en','imported');"
    $deleteSql = "DELETE FROM problem WHERE id=$probeId;"

    $inserted = $false
    try {
        Invoke-Mysql $insertSql | Out-Null
        $inserted = $true

        $r = Invoke-Search '/search/admin/reindex' $null $token
        $body = $r.body | ConvertFrom-Json
        Check 'guard.probeIndexed' ($body.data.documents -eq ($ExpectedDocs + 1)) "documents=$($body.data.documents)"

        $probeFile = Join-Path $script:tmp 'p5-q-probe.json'
        Write-Utf8Json $probeFile ('{"query":"' + $probeContent + '","mode":"bm25","topK":5}')
        $r = Invoke-Search '/search/query' $probeFile $token
        $body = $r.body | ConvertFrom-Json
        $ids = @($body.data.hits | ForEach-Object { $_.problemId })
        Check 'guard.probeContentSearchable' ($ids -contains $probeId) "ids=$($ids -join ',')"

        Write-Utf8Json $probeFile ('{"query":"' + $probeAnswer + '","mode":"bm25","topK":5}')
        $r = Invoke-Search '/search/query' $probeFile $token
        $body = $r.body | ConvertFrom-Json
        $ids = @($body.data.hits | ForEach-Object { $_.problemId })
        Check 'guard.answerNotSearchable' (-not ($ids -contains $probeId)) "ids=$($ids -join ',')"
    } finally {
        if ($inserted) {
            Invoke-Mysql $deleteSql | Out-Null
            $r = Invoke-Search '/search/admin/reindex' $null $token
            $body = $r.body | ConvertFrom-Json
            Check 'guard.probeCleaned' ($body.data.documents -eq $ExpectedDocs) "documents=$($body.data.documents)"
        }
    }

    $emptyFile = Join-Path $script:tmp 'p5-q-empty.json'
    Write-Utf8Json $emptyFile '{"query":"   ","mode":"bm25"}'
    $r = Invoke-Search '/search/query' $emptyFile $token
    Check 'guard.emptyQuery400' ($r.status -eq 400) "status=$($r.status)"
    Check 'guard.emptyQueryCode' ((($r.body | ConvertFrom-Json).code) -eq 30004)

    # hybrid is implemented since P5b: assert the real contract, not the old 501.
    $hybridFile = Join-Path $script:tmp 'p5-q-hybrid.json'
    Write-Utf8Json $hybridFile '{"query":"game","mode":"hybrid","topK":5}'
    $r = Invoke-Search '/search/query' $hybridFile $token
    $hybridBody = $r.body | ConvertFrom-Json
    Check 'guard.hybrid200' ($r.status -eq 200) "status=$($r.status)"
    Check 'guard.hybridHasHits' (@($hybridBody.data.hits).Count -gt 0) "hits=$(@($hybridBody.data.hits).Count)"
    Check 'guard.hybridRanksPresent' ($null -ne $hybridBody.data.hits[0].rrfScore -and $null -ne $hybridBody.data.hits[0].bm25Rank -and $null -ne $hybridBody.data.hits[0].denseRank)

    $topkFile = Join-Path $script:tmp 'p5-q-topk.json'
    Write-Utf8Json $topkFile '{"query":"game","mode":"bm25","topK":51}'
    $r = Invoke-Search '/search/query' $topkFile $token
    Check 'guard.topK400' ($r.status -eq 400) "status=$($r.status)"

    $r = Invoke-Search '/search/query' $enFile ''
    Check 'guard.noTokenRejected' ((($r.body | ConvertFrom-Json).code) -eq 10005) "body=$($r.body)"
}

if ($Scenario -in @('sync', 'all')) {
    Write-Host '-- sync'
    $probeId = 999002
    $oldToken = 'syncprobezz'
    $newToken = 'syncprobe2zz'
    $insertSync = "INSERT INTO problem (id,title,content,difficulty,tags,answer,submit_num,accepted_num,judge_case,judge_config,thumb_num,favour_num,user_id,create_time,update_time,is_delete,lang,source) VALUES ($probeId,'p5 sync probe','This statement contains only the token $oldToken.','EASY','[]','','0','0','[]','{}','0','0','1',NOW(3),NOW(3),0,'en','imported');"
    $cleanSync = "DELETE FROM problem WHERE id=$probeId;"

    function Find-Probe([string]$query) {
        $f = Join-Path $script:tmp 'p5-q-sync.json'
        Write-Utf8Json $f ('{"query":"' + $query + '","mode":"bm25","topK":10}')
        $resp = Invoke-Search '/search/query' $f $token
        $parsed = $resp.body | ConvertFrom-Json
        return @($parsed.data.hits | ForEach-Object { $_.problemId })
    }

    Invoke-Mysql $cleanSync | Out-Null
    $inserted = $false
    try {
        Invoke-Mysql $insertSync | Out-Null
        $inserted = $true
        $r = Invoke-Search '/search/admin/reindex' $null $token
        $body = $r.body | ConvertFrom-Json
        Check 'sync.probeIndexed' ($body.data.documents -eq ($ExpectedDocs + 1)) "documents=$($body.data.documents)"
        Check 'sync.probeSearchable' ((Find-Probe $oldToken) -contains $probeId)

        # Edit through the admin API: the event path must update the document
        # without anyone running a reindex.
        $updateFile = Join-Path $script:tmp 'p5-sync-update.json'
        $updateJson = '{"id":' + $probeId + ',"title":"p5 sync probe","content":"This statement now contains only the token ' + $newToken + '.","difficulty":"EASY","tags":[],"answer":"","judgeCase":[],"judgeConfig":{"timeLimit":1000,"memoryLimit":256}}'
        Write-Utf8Json $updateFile $updateJson
        $r = Invoke-Search '/problem' $updateFile $token 'PUT'
        Check 'sync.updateHttp200' ($r.status -eq 200) "status=$($r.status)"
        $seen = Wait-Until { (Find-Probe $newToken) -contains $probeId } 20
        Check 'sync.editIndexedWithoutReindex' $seen
        Check 'sync.oldContentGone' (-not ((Find-Probe $oldToken) -contains $probeId))

        # The document must carry the newer update_time: read it back from ES and
        # compare with MySQL. This is the external-version guarantee in practice.
        $esDoc = Get-EsJson "/$Index/_doc/$probeId"
        $dbVersion = [long](Invoke-Mysql "SELECT UNIX_TIMESTAMP(update_time)*1000 FROM problem WHERE id=$probeId;")
        Check 'sync.externalVersionIsDbUpdateTime' ([math]::Abs([long]$esDoc.'_source'.updateTime - $dbVersion) -le 1) "es=$($esDoc.'_source'.updateTime) db=$dbVersion"

        # An out-of-order write (older external version) must be rejected by ES.
        $staleFile = Join-Path $script:tmp 'p5-sync-stale.json'
        Write-Utf8Json $staleFile '{"problemId":999002}'
        $staleOut = Join-Path $script:tmp 'p5-stale.out'
        $staleStatus = & curl.exe -s -o $staleOut -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary "@$staleFile" "$EsUrl/$Index/_doc/$probeId`?version=1&version_type=external"
        Remove-Item $staleOut -Force -ErrorAction SilentlyContinue
        Check 'sync.staleWriteRejected' ([int]$staleStatus -eq 409) "status=$staleStatus"

        # Soft delete: hidden immediately through the event path as well.
        $r = Invoke-Search "/problem/$probeId" $null $token 'DELETE'
        Check 'sync.deleteHttp200' ($r.status -eq 200) "status=$($r.status)"
        $gone = Wait-Until { -not ((Find-Probe $newToken) -contains $probeId) } 20
        Check 'sync.softDeleteHidden' $gone
        $flag = [string](Invoke-Mysql "SELECT is_delete FROM problem WHERE id=$probeId;")
        Check 'sync.softDeleteFlagInDb' ($flag.Trim() -eq '1') "is_delete=$flag"
    } finally {
        Invoke-Mysql $cleanSync | Out-Null
        $r = Invoke-Search '/search/admin/reindex' $null $token
        $body = $r.body | ConvertFrom-Json
        Check 'sync.probeCleaned' ($body.data.documents -eq $ExpectedDocs) "documents=$($body.data.documents)"
    }
}
if ($Scenario -in @('watermark', 'all')) {
    Write-Host '-- watermark'
    $wmId = 999003
    $wmToken = 'watermarkprobezz'
    $wmInsert = "INSERT INTO problem (id,title,content,difficulty,tags,answer,submit_num,accepted_num,judge_case,judge_config,thumb_num,favour_num,user_id,create_time,update_time,is_delete,lang,source) VALUES ($wmId,'p5 watermark probe','This statement contains only the token $wmToken.','EASY','[]','','0','0','[]','{}','0','0','1',NOW(3),NOW(3),0,'en','imported');"
    $wmClean = "DELETE FROM problem WHERE id=$wmId;"

    Invoke-Mysql $wmClean | Out-Null
    Invoke-Mysql $wmInsert | Out-Null
    $foundWatermark = Wait-Until {
        $f = Join-Path $script:tmp 'p5-q-wm.json'
        Write-Utf8Json $f ('{"query":"' + $wmToken + '","mode":"bm25","topK":10}')
        $resp = Invoke-Search '/search/query' $f $token
        $parsed = $resp.body | ConvertFrom-Json
        @($parsed.data.hits | ForEach-Object { $_.problemId }) -contains $wmId
    } 100
    Check 'watermark.picksUpDirectInsert' $foundWatermark

    # Logical delete behind the event's back: the watermark must still write a
    # tombstone. The old implementation missed this because selectList filters
    # is_delete=1 automatically.
    #
    # ASCII only: Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI, and a
    # non-ASCII comment can swallow the following line (the UPDATE below then
    # never runs, with exit code 0 and no output). Keep every comment ASCII.
    Invoke-Mysql "UPDATE problem SET is_delete=1, update_time=NOW(3) WHERE id=$wmId;" | Out-Null

    # Split the assertion in two so a failure says whether the UPDATE reached
    # MySQL at all or the watermark failed to mirror it into ES.
    $wmFlag = ([string](Invoke-Mysql "SELECT is_delete FROM problem WHERE id=$wmId;")).Trim()
    Check 'watermark.softDeleteReachedDb' ($wmFlag -eq '1') "is_delete=$wmFlag"

    $tombstone = Wait-Until {
        $doc = Get-EsJson "/$Index/_doc/$wmId"
        $doc.'_source'.isDelete -eq $true
    } 100
    Check 'watermark.repairsMissedSoftDelete' $tombstone

    Invoke-Mysql $wmClean | Out-Null
    & curl.exe -s -X DELETE "$EsUrl/$Index/_doc/$wmId" | Out-Null
}

if ($Scenario -in @('reindex', 'all')) {
    Write-Host '-- reindex fidelity'
    $r = Invoke-Search '/search/admin/reindex' $null $token
    $body = $r.body | ConvertFrom-Json
    Check 'reindex.documents' ($body.data.documents -eq $ExpectedDocs) "documents=$($body.data.documents)"

    $rows = Invoke-Mysql "SELECT id FROM problem WHERE is_delete=0 ORDER BY RAND() LIMIT 5;"
    $compared = 0
    $mismatch = @()
    foreach ($row in $rows) {
        $id = ([string]$row).Trim()
        if (-not $id) { continue }
        $db = Invoke-Mysql "SELECT title, difficulty, lang, source, is_delete, UNIX_TIMESTAMP(update_time)*1000 FROM problem WHERE id=$id;"
        $parts = ([string]$db).Trim() -split "`t"
        $doc = Get-EsJson "/$Index/_doc/$id"
        $src = $doc.'_source'
        $compared++
        if ($src.problemId -ne [long]$id) { $mismatch += "$id:problemId" }
        if ($src.title -ne $parts[0]) { $mismatch += "$id:title" }
        if ($src.difficulty -ne $parts[1]) { $mismatch += "$id:difficulty" }
        if ($src.lang -ne $parts[2]) { $mismatch += "$id:lang" }
        if ($src.source -ne $parts[3]) { $mismatch += "$id:source" }
        if (([bool]$src.isDelete) -ne ($parts[4] -eq '1')) { $mismatch += "$id:isDelete" }
        if ([math]::Abs([long]$src.updateTime - [long]([double]$parts[5])) -gt 1) { $mismatch += "$id:updateTime" }
    }
    Check 'reindex.fiveDocsFieldEqual' ($compared -eq 5 -and $mismatch.Count -eq 0) "compared=$compared mismatch=$($mismatch -join ',')"
}
function Percentile([double[]]$values, [double]$p) {
    if ($values.Count -eq 0) { return 0 }
    $sorted = $values | Sort-Object
    $index = [math]::Ceiling(($p / 100.0) * $sorted.Count) - 1
    if ($index -lt 0) { $index = 0 }
    return [math]::Round($sorted[$index], 1)
}

function Get-RelevantIds([long]$problemId) {
    $doc = Get-EsJson "/$Index/_doc/$problemId"
    $tags = @($doc.'_source'.tags)
    $difficulty = $doc.'_source'.difficulty
    $neighbours = switch ($difficulty) {
        'EASY' { @('EASY', 'MEDIUM') }
        'MEDIUM' { @('EASY', 'MEDIUM', 'HARD') }
        'HARD' { @('MEDIUM', 'HARD') }
        default { @($difficulty) }
    }
    $body = @{
        size = 500
        _source = $false
        query = @{ bool = @{ should = @(
            @{ terms = @{ tags = $tags } },
            @{ terms = @{ difficulty = $neighbours } }
        ); minimum_should_match = 1 } }
    } | ConvertTo-Json -Depth 8 -Compress
    $file = Join-Path $script:tmp 'p5-rel.json'
    Write-Utf8Json $file $body
    $raw = & curl.exe -s -X POST -H 'Content-Type: application/json' --data-binary "@$file" "$EsUrl/$Index/_search"
    $parsed = ([string]::Join('', $raw) | ConvertFrom-Json)
    return @($parsed.hits.hits | ForEach-Object { [long]$_.'_id' })
}

if ($Scenario -in @('eval', 'all')) {
    Write-Host '-- eval'
    $fixture = Join-Path (Get-Location) 'benchmark/fixtures/p5-eval.json'
    $queries = (Get-Content $fixture -Encoding UTF8 -Raw | ConvertFrom-Json).queries
    Check 'eval.fixtureSize' ($queries.Count -eq $script:expected.evalQueries) "queries=$($queries.Count)"

    $runs = @()
    $noHits = 0
    foreach ($q in $queries) {
        $body = @{ query = $q.query; mode = $Mode; topK = 50; lang = $q.lang } | ConvertTo-Json -Compress
        $file = Join-Path $script:tmp 'p5-eval-q.json'
        Write-Utf8Json $file $body
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $r = Invoke-Search '/search/query' $file $token
        $sw.Stop()
        if ($r.status -ne 200) { throw "eval query $($q.id) failed: HTTP $($r.status) $($r.body)" }
        $parsed = $r.body | ConvertFrom-Json
        $hits = @($parsed.data.hits | ForEach-Object { [long]$_.problemId })
        if ($hits.Count -eq 0) { $noHits++ }
        $runs += [pscustomobject]@{
            id = $q.id
            lang = $q.lang
            band = $q.band
            problemId = [long]$q.problemId
            hits = $hits
            relevant = (Get-RelevantIds ([long]$q.problemId))
            latencyMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 1)
        }
    }

    $resultsDir = Join-Path (Get-Location) 'benchmark/results'
    New-Item -ItemType Directory -Force $resultsDir | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $resultsPath = Join-Path $resultsDir "p5-eval-$Mode-$stamp.json"
    $payload = @{ mode = $Mode; index = $Index; queries = $queries.Count; runs = $runs }
    Write-Utf8Json $resultsPath ($payload | ConvertTo-Json -Depth 6)

    $pyOut = & $PythonExe 'benchmark/eval_p5.py' --input $resultsPath --markdown
    $metricsLine = $pyOut[-1]
    $metrics = $metricsLine | ConvertFrom-Json
    Write-Host ($pyOut[0..($pyOut.Count - 2)] -join "`n")

    Check 'eval.queriesWithHits' (($queries.Count - $noHits) -ge [int]$script:expected.queriesWithHitsFloor) "noHits=$noHits"
    Check 'eval.hitRate10Floor' ($metrics.overall.'HitRate@10' -ge $script:expected.hitRate10Floor) "hit@10=$($metrics.overall.'HitRate@10')"
    Check 'eval.mrrFloor' ($metrics.overall.MRR -ge $script:expected.mrrFloor) "mrr=$($metrics.overall.MRR)"
    Check 'eval.recallInRange' ($metrics.overall.'Recall@10' -ge 0 -and $metrics.overall.'Recall@10' -le 1)
    Check 'eval.englishTable' ($null -ne $metrics.byLang.en) "en missing"
    Check 'eval.chineseTable' ($null -ne $metrics.byLang.zh) "zh missing"

    $bands = @{}
    foreach ($q in $queries) { $bands[$q.band] = 1 + [int]$bands[$q.band] }
    $bandOk = $true
    foreach ($name in $script:expected.bandCounts.PSObject.Properties.Name) {
        if ([int]$bands[$name] -ne [int]$script:expected.bandCounts.$name) { $bandOk = $false }
    }
    Check 'eval.bandCounts' $bandOk "bands=$($bands | ConvertTo-Json -Compress)"
    Write-Host "eval.results=$resultsPath"
}

if ($Scenario -in @('latency', 'all')) {
    Write-Host '-- latency'
    $fixture = Join-Path (Get-Location) 'benchmark/fixtures/p5-eval.json'
    $queries = (Get-Content $fixture -Encoding UTF8 -Raw | ConvertFrom-Json).queries
    $warmup = 20
    $total = [int]$script:expected.latencySamples
    $clientMs = New-Object System.Collections.Generic.List[double]
    $serverMs = New-Object System.Collections.Generic.List[double]
    $embedMs = New-Object System.Collections.Generic.List[double]
    for ($i = 0; $i -lt ($warmup + $total); $i++) {
        $q = $queries[$i % $queries.Count]
        $body = @{ query = $q.query; mode = $Mode; topK = 10; lang = $q.lang } | ConvertTo-Json -Compress
        $file = Join-Path $script:tmp 'p5-lat-q.json'
        Write-Utf8Json $file $body
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $r = Invoke-Search '/search/query' $file $token
        $sw.Stop()
        if ($i -ge $warmup) {
            $clientMs.Add($sw.Elapsed.TotalMilliseconds)
            $parsed = $r.body | ConvertFrom-Json
            $serverMs.Add([double]$parsed.data.esMs)
            $embedMs.Add([double]$parsed.data.embedMs)
        }
    }
    $p50 = Percentile $clientMs.ToArray() 50
    $p95 = Percentile $clientMs.ToArray() 95
    $esP95 = Percentile $serverMs.ToArray() 95
    $embedP95 = Percentile $embedMs.ToArray() 95
    Write-Host ("latency.mode={0} client p50={1}ms p95={2}ms | server es p95={3}ms | embed p95={4}ms | n={5}" -f $Mode, $p50, $p95, $esP95, $embedP95, $clientMs.Count)
    Check 'latency.sampleCount' ($clientMs.Count -eq $script:expected.latencySamples) "n=$($clientMs.Count)"
    Check 'latency.p95UnderBudget' ($p95 -lt $script:expected.latencyP95Ms) "p95=$p95"
    Check 'latency.serverEsReported' ($esP95 -gt 0) "esP95=$esP95"
}
if ($Scenario -eq 'skill-eval') {
    # Ticket 04: skill index -> search endpoint -> Hit@10 / MRR + visibility.
    # Not part of -Scenario all on purpose: it seeds 45 rows into learning_skill,
    # which would change the counts other scenarios assert.
    Write-Host '-- skill-eval'
    $mysqlPassword = $env:OJ_MYSQL_PASSWORD
    if (-not $mysqlPassword) {
        throw 'OJ_MYSQL_PASSWORD is not set: read the password from .runtime/run/judge/application.yml and export it'
    }
    # The token, the password and the JWT secret reach the child through the
    # environment: a command line is readable by every process on the box
    # (docs/SECURITY.md). skill_eval.py reads OJ_ADMIN_TOKEN.
    $env:OJ_ADMIN_TOKEN = $token
    $out = & $PythonExe 'benchmark/skill_eval.py' --base-url $BaseUrl --topk 10
    if ($LASTEXITCODE -ne 0) { throw "skill eval failed with exit $LASTEXITCODE" }
    $summary = ($out[-1] | ConvertFrom-Json)
    Write-Host ($out[0..($out.Count - 2)] -join "`n")
    Check 'skillEval.queries' ($summary.queries -eq 20) "n=$($summary.queries)"
    Check 'skillEval.visibilityOk' ([bool]$summary.visibilityOk)
    Check 'skillEval.hitRateAt10NotZero' ($summary.hitRateAt10 -gt 0) "hit@10=$($summary.hitRateAt10)"
    Check 'skillEval.mrrNotZero' ($summary.mrr -gt 0) "mrr=$($summary.mrr)"
    Check 'skillEval.indexed' ($summary.indexed -ge 44 -and $summary.indexedFailed -eq 0) "indexed=$($summary.indexed) failed=$($summary.indexedFailed)"
    Check 'skillEval.baselineBelowHalf' ($summary.randomBaseline -lt 0.5) "baseline=$($summary.randomBaseline) visible=$($summary.visibleDocuments)"
    Write-Host 'skillEval.results=benchmark/results/skill-eval.json'
}
if ($Scenario -eq 'skill-promote') {
    # Ticket 05: threshold -> promote -> idempotent -> self-approval refused ->
    # immediately searchable by *another* user. Needs the p8 migration applied.
    Write-Host '-- skill-promote'
    $mysqlPassword = $env:OJ_MYSQL_PASSWORD
    if (-not $mysqlPassword) {
        throw 'OJ_MYSQL_PASSWORD is not set: read the password from .runtime/run/judge/application.yml and export it'
    }
    $out = & $PythonExe 'benchmark/skill_promotion_demo.py'
    if ($LASTEXITCODE -ne 0) { throw "skill promotion demo failed with exit $LASTEXITCODE" }
    $checks = ($out[-1] | ConvertFrom-Json)
    Write-Host ($out[0..($out.Count - 2)] -join "`n")
    Check 'promotion.platformRowCreated' ([bool]$checks.platformRowCreated)
    Check 'promotion.personalRowUnchanged' ([bool]$checks.personalRowUnchanged)
    Check 'promotion.repeatedIsIdempotent' ([bool]$checks.repeatedPromoteIsIdempotent)
    Check 'promotion.authorCannotApprove' ([bool]$checks.authorCannotApproveOwnSkill)
    Check 'promotion.visibleAfterPromotion' ([bool]$checks.visibleToOtherUserOnlyAfterPromotion)
    Check 'promotion.indexed' ([bool]$checks.promotedRowIndexed)
    Check 'promotion.candidateEvidence' ([bool]$checks.candidateExplainsEvidence)
    Write-Host 'promotion.results=benchmark/results/skill-promotion.json'
}
if ($Scenario -eq 'skill-cleanup') {
    # Teardown for the skill fixtures: deletes the 45 corpus rows and any
    # promoted copies, then rebuilds the index to 0 documents. Never part of
    # -Scenario all -- it removes the corpus the skill-eval needs.
    Write-Host '-- skill-cleanup'
    $removed = Invoke-Mysql ("DELETE FROM learning_skill WHERE id IN (" +
        "9100000000000000001, 9100000000000000002, 9100000000000000003, 9100000000000000004, " +
        "9100000000000000005, 9100000000000000006, 9100000000000000007, 9100000000000000008, " +
        "9100000000000000009, 9100000000000000010, 9100000000000000011, 9100000000000000012, " +
        "9100000000000000013, 9100000000000000014, 9100000000000000015, 9100000000000000016, " +
        "9100000000000000017, 9100000000000000018, 9100000000000000019, 9100000000000000020, " +
        "9100000000000000021, 9100000000000000022, 9100000000000000023, 9100000000000000024, " +
        "9100000000000000025, 9100000000000000026, 9100000000000000027, 9100000000000000028, " +
        "9100000000000000029, 9100000000000000030, 9100000000000000031, 9100000000000000032, " +
        "9100000000000000033, 9100000000000000034, 9100000000000000035, 9100000000000000036, " +
        "9100000000000000037, 9100000000000000038, 9100000000000000039, 9100000000000000040, " +
        "9200000000000000001, 9200000000000000002, 9200000000000000003, 9200000000000000004, " +
        "9150000000000000001);" +
        "DELETE FROM learning_skill WHERE origin_skill_id IN (9200000000000000001, 9200000000000000002);" +
        "DELETE FROM learning_skill_usage WHERE skill_id IN (9200000000000000001, 9200000000000000002);")
    $r = Invoke-Search '/search/skill/admin/reindex' $null $token
    $body = $r.body | ConvertFrom-Json
    Check 'skillCleanup.indexEmpty' ($body.data.documents -eq 0) "documents=$($body.data.documents)"
    $left = [string](Invoke-Mysql ("SELECT COUNT(*) FROM learning_skill WHERE id IN " +
        "(9100000000000000001, 9200000000000000001, 9150000000000000001);"))
    Check 'skillCleanup.corpusGone' ($left.Trim() -eq '0') "left=$left"
}
if ($Scenario -eq 'cleanup') {
    # Teardown only: wipes the imported corpus (500 en + 20 zh) and leaves the
    # three native problems. Never part of -Scenario all -- it destroys the
    # evaluation corpus. Re-import with benchmark/import-p5-corpus.ps1.
    Write-Host '-- cleanup'
    $removed = Invoke-Mysql "DELETE FROM problem WHERE source='imported';"
    $r = Invoke-Search '/search/admin/reindex' $null $token
    $body = $r.body | ConvertFrom-Json
    Check 'cleanup.documentsAfterWipe' ($body.data.documents -eq 3) "documents=$($body.data.documents)"
    $left = [string](Invoke-Mysql "SELECT COUNT(*) FROM problem WHERE source='imported';")
    Check 'cleanup.importedRowsGone' ($left.Trim() -eq '0') "imported=$left"
}
if ($Scenario -eq 'compare') {
    # Run the same 100 queries through all three modes, then aggregate in Python
    # so the three columns come from one code path.
    Write-Host '-- compare (bm25 / dense / hybrid)'
    $files = @{}
    foreach ($method in @('bm25', 'dense', 'hybrid')) {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $PSCommandPath -Scenario eval -Mode $method *> $null
        if ($LASTEXITCODE -ne 0) { throw "eval mode=$method failed with exit $LASTEXITCODE" }
        $latest = Get-ChildItem 'benchmark/results' -Filter "p5-eval-$method-*.json" | Sort-Object LastWriteTime | Select-Object -Last 1
        if ($null -eq $latest) { throw "no eval result for mode=$method" }
        $files[$method] = $latest.FullName
    }

    $pyOut = & $PythonExe 'benchmark/compare_p5.py' --bm25 $files['bm25'] --dense $files['dense'] --hybrid $files['hybrid']
    $metrics = $pyOut[-1] | ConvertFrom-Json
    Write-Host ($pyOut[0..($pyOut.Count - 2)] -join "`n")

    $bm25 = $metrics.bm25.overall.'HitRate@10'
    $dense = $metrics.dense.overall.'HitRate@10'
    $hybrid = $metrics.hybrid.overall.'HitRate@10'
    Check 'compare.threeMethodsPresent' ($null -ne $bm25 -and $null -ne $dense -and $null -ne $hybrid)
    Check 'compare.hybridNotBelowWorseArm' ($hybrid -ge ([math]::Min($bm25, $dense) - 0.05)) "hybrid=$hybrid bm25=$bm25 dense=$dense"
    Check 'compare.hybridBeatsBm25' ($hybrid -ge $bm25) "hybrid=$hybrid bm25=$bm25"
}
Write-Host ("assertions={0} fail={1}" -f $script:assertions, $script:failures)
if ($script:failures -gt 0) { exit 1 }
exit 0
