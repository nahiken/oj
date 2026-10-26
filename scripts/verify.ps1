# One local gate for P7 (ticket 10). ASCII only: Windows PowerShell 5.1 reads
# a BOM-less .ps1 as ANSI.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify.ps1
#
# Five steps, in order: Java unit tests -> pytest -> frontend type-check ->
# AI smoke through the gateway -> browser path. Any failure fails the run.
#
# It expects the local lab to be up (see docs/RELIABILITY.md):
#   Nacos 8858, MySQL 3306, Redis 6379, MQ 5672, judge 8012, sandbox 8013,
#   user 8011, gateway 8024, ai 8026, ES 9200, embedding 8025.
# The AI must run with its DEFAULT limits (6/60) and P6_FAKE_DELAY_MS >= 1000;
# both are checked before anything is run, because a benchmark failure caused by
# the wrong environment is not a product regression.
# The frontend dev server is started here if 8002 is not answering, and stopped
# again on exit.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$script:failures = 0
$script:steps = 0
$devProcess = $null
$browserUid = $null
$browserEmail = $null

function Step([string]$name, [scriptblock]$body) {
    $script:steps++
    Write-Host ""
    Write-Host ("== {0}" -f $name)
    & $body
    if ($LASTEXITCODE -ne 0) {
        $script:failures++
        Write-Host ("FAIL {0} (exit {1})" -f $name, $LASTEXITCODE)
    } else {
        Write-Host ("ok   {0}" -f $name)
    }
}

function Test-Port([int]$port) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $port)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Wait-Port([int]$port, [int]$seconds, [string]$name) {
    for ($i = 0; $i -lt $seconds; $i++) {
        if (Test-Port $port) { return $true }
        Start-Sleep -Seconds 1
    }
    throw "$name is not listening on port $port after $seconds s"
}

try {
    # ---------------- preflight ----------------
    foreach ($required in @(
            @{ port = 8858; name = 'Nacos' },
            @{ port = 3306; name = 'MySQL' },
            @{ port = 6379; name = 'Redis' },
            @{ port = 8012; name = 'judge' },
            @{ port = 8013; name = 'sandbox' },
            @{ port = 8011; name = 'user' },
            @{ port = 8024; name = 'gateway' },
            @{ port = 8026; name = 'ai' },
            @{ port = 9200; name = 'Elasticsearch' },
            @{ port = 8025; name = 'embedding sidecar' })) {
        if (-not (Test-Port $required.port)) {
            throw ("{0} is down (port {1}); see docs/RELIABILITY.md" -f $required.name, $required.port)
        }
    }

    $metrics = Invoke-RestMethod -Uri 'http://127.0.0.1:8026/ai/metrics' -Headers @{ 'X-Uid' = '1' }
    if ($metrics.limits.uidPerMin -ne 6 -or $metrics.limits.globalPerMin -ne 60) {
        throw ("ai must run with the default limits (6/60) for the smoke and 429 checks; " +
            "got {0}/{1}" -f $metrics.limits.uidPerMin, $metrics.limits.globalPerMin)
    }
    if ($null -eq $metrics.fakeDelayMs -or $metrics.fakeDelayMs -lt 1000) {
        throw ("start ai with P6_FAKE_DELAY_MS=1000 (fake provider) so the browser test " +
            "has a stream to stop")
    }

    # The smoke scenario needs the same secrets the services use; read them from
    # the local (gitignored) config and only export them to child processes.
    $judgeYml = Get-Content '.runtime/run/judge/application.yml' -Raw
    $mysqlPassword = [regex]::Match($judgeYml, 'password:\s*(\S+)').Groups[1].Value
    $jwtSecret = [regex]::Match($judgeYml, 'secret-key:\s*(\S+)').Groups[1].Value
    $env:OJ_MYSQL_PASSWORD = $mysqlPassword
    # `mysql` reads the password from MYSQL_PWD; run-p6.ps1 sets it the same way.
    $env:MYSQL_PWD = $mysqlPassword
    $env:OJ_JWT_SECRET = $jwtSecret
    $env:JAVA_HOME = 'D:\work\environment\jdk-21'

    # ---------------- 1. Java unit tests ----------------
    Step 'java unit tests' {
        Push-Location 'oj-backend-master'
        try {
            mvn -s ../.runtime/maven-settings.xml -pl judge -am `
                -Dtest='!ProblemSubmitServiceTest' -DfailIfNoTests=false test
        } finally {
            Pop-Location
        }
    }

    # ---------------- 2. Python unit tests ----------------
    Step 'python unit tests' {
        # benchmark/test_second_ac.py is the metric's window-boundary suite; it
        # needs MySQL, which the preflight above already requires.
        & 'D:\python\python.exe' -m pytest ai/tests benchmark/test_second_ac.py -q
    }

    # ---------------- 3. frontend type-check ----------------
    Step 'frontend type-check' {
        Push-Location 'oj-frontend-master'
        try {
            pnpm type-check
        } finally {
            Pop-Location
        }
    }

    # ---------------- 4. AI smoke through the gateway ----------------
    Step 'ai smoke (default limits, one real 429)' {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'benchmark/run-p6.ps1' -Scenario smoke
    }

    # ---------------- 5. browser path ----------------
    Step 'browser path (playwright)' {
        if (-not (Test-Port 8002)) {
            Write-Host 'starting the frontend dev server (cold start budget: 60 s)'
            $devProcess = Start-Process -FilePath 'cmd.exe' `
                -ArgumentList '/c', 'pnpm dev' `
                -WorkingDirectory (Join-Path $root 'oj-frontend-master') `
                -PassThru -WindowStyle Hidden
            Wait-Port 8002 60 'frontend dev server' | Out-Null
        }

        # A fresh uid per run: the AI's token bucket is in-process, so reusing one
        # account makes a rerun inside the same minute start already limited.
        $browserUid = 9900001000 + (Get-Random -Maximum 8999)
        $browserEmail = "p7-browser-$browserUid@example.invalid"
        & 'D:\python\python.exe' 'scripts/seed_p7_browser_user.py' --uid $browserUid --email $browserEmail
        if ($LASTEXITCODE -ne 0) { throw 'could not seed the browser account' }

        $env:P7_EMAIL = $browserEmail
        $env:P7_SCREENSHOT_DIR = Join-Path $root 'docs/generated'
        $playwrightExit = 0
        Push-Location 'oj-frontend-master'
        try {
            pnpm exec playwright test
            $playwrightExit = $LASTEXITCODE
        } finally {
            Pop-Location
        }

        # The browser run must also have produced a session row: that is the
        # difference between "the UI rendered a stream" and "the product recorded
        # a tutoring session".
        # Ask the Python helper instead of shelling out to mysql: the password
        # stays in one place (scripts/runtime_config.py) and this script never
        # needs to know it.
        $sessionRows = & 'D:\python\python.exe' 'scripts/seed_p7_browser_user.py' `
            --uid $browserUid --count-sessions
        $rows = [int]([string]$sessionRows).Trim()
        Write-Host ("browser sessions recorded: {0}" -f $rows)
        if ($rows -lt 1) {
            throw "the browser run left no ai_tutoring_session row for uid $browserUid"
        }
        $global:LASTEXITCODE = $playwrightExit
    }
} finally {
    if ($browserUid) {
        & 'D:\python\python.exe' 'scripts/seed_p7_browser_user.py' --uid $browserUid --cleanup | Out-Null
    }
    if ($devProcess) {
        & taskkill /PID $devProcess.Id /T /F 2>&1 | Out-Null
    }
    Write-Host ""
    Write-Host ("steps={0} failed={1}" -f $script:steps, $script:failures)
    if ($script:failures -gt 0 -or $script:steps -lt 5) { exit 1 }
}
