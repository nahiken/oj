param(
  [string]$Mysql = 'D:\mysql\mysql\bin\mysql.exe',
  [string]$MysqlUser = 'root',
  [string]$MysqlPassword = $env:OJ_MYSQL_PASSWORD,
  [string]$RedisCli = 'D:\developer\env\Redis-x64-5.0.14.1\redis-cli.exe',
  [int]$RedisDb = 12,
  [string]$Gateway = 'http://localhost:8024',
  [string]$Email = 'bench@example.com',
  [string]$Code = '123456'
)

$ErrorActionPreference = 'Stop'

if (-not $MysqlPassword) {
  throw '缺少 MySQL 密码：设置环境变量 OJ_MYSQL_PASSWORD 或传 -MysqlPassword'
}

$repo = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $repo '.runtime'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null

Write-Host '[setup] seed problem...'
Get-Content (Join-Path $PSScriptRoot 'seed.sql') -Raw | & $Mysql "-u$MysqlUser" "--password=$MysqlPassword" '--default-character-set=utf8mb4' -D oj
if ($LASTEXITCODE -ne 0) { throw 'seed.sql 执行失败' }

$problemId = (& $Mysql "-u$MysqlUser" "--password=$MysqlPassword" '--default-character-set=utf8mb4' -D oj -N -B -e "SELECT id FROM problem WHERE title='A+B Baseline' ORDER BY id LIMIT 1").Trim()
if (-not $problemId) { throw '未找到 A+B Baseline 题目' }
Write-Host "[setup] problemId=$problemId"

Write-Host '[setup] set redis mail code...'
$ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
& $RedisCli -n $RedisDb SET "user:mail:code:$Email" "${Code}_$ts" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Redis SET 失败' }

Write-Host '[setup] login by code...'
$loginBody = @{ email = $Email; captcha = $Code } | ConvertTo-Json -Compress
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$login = Invoke-WebRequest -UseBasicParsing -Uri "$Gateway/api/user/loginByCode" -Method Post -ContentType 'application/json' -Body $loginBody -WebSession $session
$cookie = $session.Cookies.GetCookies([Uri]$Gateway) | Where-Object { $_.Name -eq 'token' } | Select-Object -First 1
if (-not $cookie) {
  throw "登录失败：HTTP $($login.StatusCode) body=$($login.Content)"
}

$cookieHeader = "token=$($cookie.Value)"
Set-Content -Path (Join-Path $runtime 'cookie.txt') -Value $cookieHeader -NoNewline
Write-Host '[setup] cookie saved to .runtime/cookie.txt'

$javaCode = @'
import java.util.*;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.println(sc.nextInt() + sc.nextInt());
    }
}
'@

$submitBody = @{
  problemId = [long]$problemId
  language  = 'Java'
  code      = $javaCode
} | ConvertTo-Json -Compress
Set-Content -Path (Join-Path $runtime 'submit-body.json') -Value $submitBody -NoNewline

$queryBody = @{ current = 1; size = 10 } | ConvertTo-Json -Compress
Set-Content -Path (Join-Path $runtime 'query-body.json') -Value $queryBody -NoNewline

$runBody = @{
  language  = 'Java'
  code      = $javaCode
  inputList = @('1 2', '10 20')
} | ConvertTo-Json -Compress
Set-Content -Path (Join-Path $runtime 'run-body.json') -Value $runBody -NoNewline

Write-Host '[setup] body files saved to .runtime/'
Write-Host '[setup] done'
