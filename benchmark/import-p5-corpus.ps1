# Load (or clean) the P5 corpus in the local MySQL.
# ASCII only: Windows PowerShell 5.1 reads .ps1 as ANSI.
#
#   $env:OJ_MYSQL_PASSWORD='<pw>'
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\benchmark\import-p5-corpus.ps1
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\benchmark\import-p5-corpus.ps1 -Cleanup
#
# Expects the fixtures produced by benchmark/fetch_p5_corpus.py.

param(
    [switch]$Cleanup,
    [switch]$SkipDdl
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

if (-not $env:OJ_MYSQL_PASSWORD) {
    throw "set OJ_MYSQL_PASSWORD (see .runtime/run/judge/application.yml)"
}
$env:MYSQL_PWD = $env:OJ_MYSQL_PASSWORD

$mysql = "D:\mysql\mysql\bin\mysql.exe"
$db = "oj"
if (-not (Test-Path $mysql)) { throw "mysql client not found: $mysql" }

function Invoke-Sql([string]$sql) {
    & $mysql -h 127.0.0.1 -P 3306 -u root -N -B -e $sql $db
    if ($LASTEXITCODE -ne 0) { throw "mysql failed: $sql" }
}

$script:assertions = 0
$script:failures = 0
function Assert([string]$name, $actual, $expected) {
    $script:assertions++
    if ("$actual" -eq "$expected") {
        Write-Host ("PASS {0} = {1}" -f $name, $actual)
    }
    else {
        $script:failures++
        Write-Host ("FAIL {0}: expected {1}, got {2}" -f $name, $expected, $actual)
    }
}

$env:PYTHONIOENCODING = "utf-8"

if (-not $SkipDdl) {
    $hasLang = (Invoke-Sql "select count(*) from information_schema.columns where table_schema='$db' and table_name='problem' and column_name='lang'").Trim()
    if ($hasLang -eq "0") {
        Write-Host "applying sql/migration/2026-10-20-p5-search.sql"
        & $mysql -h 127.0.0.1 -P 3306 -u root $db -e "source sql/migration/2026-10-20-p5-search.sql"
        if ($LASTEXITCODE -ne 0) { throw "DDL failed" }
    }
    else {
        Write-Host "DDL already applied (problem.lang exists)"
    }
}

if ($Cleanup) {
    & python benchmark\import_p5_corpus.py --cleanup
}
else {
    & python benchmark\import_p5_corpus.py
}
if ($LASTEXITCODE -ne 0) { throw "fixture import SQL was not generated" }

Write-Host "applying .runtime/tmp/p5-import.sql"
& $mysql -h 127.0.0.1 -P 3306 -u root $db -e "source .runtime/tmp/p5-import.sql"
if ($LASTEXITCODE -ne 0) { throw "import failed" }

$row = (Invoke-Sql "select count(*), sum(source='imported' and lang='en'), sum(lang='zh'), sum(source='native'), sum(source='imported') from problem").Trim()
$parts = $row -split "\t"
$total = [int]$parts[0]
$en = [int]$parts[1]
$zh = [int]$parts[2]
$native = [int]$parts[3]
$imported = [int]$parts[4]

if ($Cleanup) {
    Assert "imported rows after cleanup" $imported 0
    Assert "native rows untouched" $native 3
}
else {
    Assert "imported rows" $imported 520
    Assert "english rows" $en 500
    Assert "chinese rows" $zh 20
    Assert "native rows" $native 3
    Assert "total rows" $total 523
    $blank = (Invoke-Sql "select count(*) from problem where source='imported' and (tags is null or tags='[]' or content='')").Trim()
    Assert "imported rows with tags and content" $blank 0
    $dup = (Invoke-Sql "select count(*) from (select id from problem group by id having count(*) > 1) t").Trim()
    Assert "duplicate ids" $dup 0
}

Write-Host ("assertions={0} fail={1}" -f $script:assertions, $script:failures)
exit $(if ($script:failures -gt 0) { 1 } else { 0 })
