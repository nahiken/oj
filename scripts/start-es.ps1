# Start a local single-node Elasticsearch for the retrieval lab.
# ASCII only: Windows PowerShell 5.1 reads .ps1 as ANSI.
#
#   .\scripts\start-es.ps1            start (installs from zips on first run)
#   .\scripts\start-es.ps1 -Install   only unpack, do not start
#
# LAB ONLY. The config below turns xpack security off and binds to loopback.
# Never point this script at a shared or production environment.
#
# The script runs ES in the foreground so the caller keeps control of the
# process. In an agent session, launch it as a background job.

param(
    [switch]$Install,
    [string]$Zip = ".runtime/tmp/elasticsearch-8.15.3.zip",
    [string]$PluginZip = ".runtime/tmp/analysis-smartcn-8.15.3.zip",
    [string]$EsHome = ".runtime/es/elasticsearch-8.15.3"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path $EsHome)) {
    if (-not (Test-Path $Zip)) {
        throw "missing $Zip - download elasticsearch-8.15.3-windows-x86_64.zip into .runtime/tmp/ first"
    }
    Write-Host "unpacking $Zip ..."
    New-Item -ItemType Directory -Force ".runtime/es" | Out-Null
    Expand-Archive -Path $Zip -DestinationPath ".runtime/es" -Force
    if (-not (Test-Path $EsHome)) { throw "unexpected archive layout: $EsHome not found after unpack" }
}

# Refuse to touch anything outside this repo's .runtime/ directory: the config
# below turns xpack security off, and a hardened environment must not be
# silently downgraded by a lab script.
$runtimeRoot = (Resolve-Path ".runtime").Path
$esResolved = (Resolve-Path $EsHome -ErrorAction SilentlyContinue)
if (-not $esResolved) { throw "ES home not found: $EsHome" }
if (-not $esResolved.Path.StartsWith($runtimeRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "refusing to run against $($esResolved.Path) - this script is LAB ONLY and only manages ES under .runtime/"
}

# analysis-smartcn is NOT bundled with the Windows distribution: it is a 3.4 MB
# plugin. `elasticsearch-plugin install` cannot be used here (it writes its
# download into the host temp dir, which the sandbox denies, and the non-ASCII
# repo path confuses the launcher), so unpack the plugin zip by hand and verify
# that it declares the same ES version.
$esJar = Get-ChildItem (Join-Path $EsHome "lib\elasticsearch-*.jar") -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $esJar) { throw "cannot determine the Elasticsearch version from $EsHome\lib" }
$esVersion = ($esJar.BaseName -replace '^elasticsearch-', '')
$pluginDir = Join-Path $EsHome "plugins\analysis-smartcn"
$descriptor = Join-Path $pluginDir "plugin-descriptor.properties"

function Get-PluginVersion([string]$path) {
    if (-not (Test-Path $path)) { return $null }
    $line = Select-String -Path $path -Pattern '^elasticsearch\.version=' | Select-Object -First 1
    if (-not $line) { return $null }
    return ($line.Line -replace '^elasticsearch\.version=', '').Trim()
}

$installed = Get-PluginVersion $descriptor
$needsInstall = ($null -eq $installed) -or ($installed -ne $esVersion)
if ($needsInstall -and (Test-Path $pluginDir)) {
    Write-Host "replacing analysis-smartcn $installed (es is $esVersion)"
    Remove-Item -Recurse -Force $pluginDir
}
if ($needsInstall) {
    if (-not (Test-Path $PluginZip)) {
        throw "missing $PluginZip - download analysis-smartcn-$esVersion.zip into .runtime/tmp/ first"
    }
    New-Item -ItemType Directory -Force $pluginDir | Out-Null
    Expand-Archive -Path $PluginZip -DestinationPath $pluginDir -Force
    $installed = Get-PluginVersion $descriptor
    if ($installed -ne $esVersion) {
        throw "analysis-smartcn $installed does not match elasticsearch $esVersion"
    }
    Write-Host "installed analysis-smartcn $installed into $pluginDir"
}

New-Item -ItemType Directory -Force ".runtime/logs" | Out-Null

if ($Install) {
    Write-Host "installed at $EsHome"
    exit 0
}

# The host sets JAVA_HOME to an incomplete JDK 11 and a CLASSPATH pointing at its
# missing dt.jar; ES runs JarHell over the classpath at boot and would die. Use
# the JDK shipped inside the distribution and drop the inherited CLASSPATH.
if (Test-Path Env:\CLASSPATH) { Remove-Item Env:\CLASSPATH }
$env:JAVA_HOME = Join-Path $EsHome "jdk"
$env:PATH = (Join-Path $EsHome "jdk\bin") + ";" + $env:PATH

# Keep every temp file inside the workspace: the host temp dir is not writable
# for the sandboxed process (ES dies on PolicyUtil temp-file creation otherwise).
$esTmp = Join-Path (Get-Location) ".runtime/tmp/es"
New-Item -ItemType Directory -Force $esTmp | Out-Null
$env:ES_TMPDIR = $esTmp
$env:ES_JAVA_OPTS = "-Xms512m -Xmx512m"
$env:ES_PATH_CONF = Join-Path $EsHome "config"

# Rewrite the lab config on every start so runs are reproducible from the script
# alone. See the LAB ONLY warning at the top of this file.
$logsDir = Join-Path (Get-Location) ".runtime/logs"
$lines = @(
    "cluster.name: lab",
    "node.name: es-1",
    "network.host: 127.0.0.1",
    "http.port: 9200",
    "discovery.type: single-node",
    "path.logs: $($logsDir -replace '\\', '/')",
    "xpack.security.enabled: false",
    "xpack.security.http.ssl.enabled: false",
    "bootstrap.memory_lock: false"
)
$cfg = Join-Path $EsHome "config\elasticsearch.yml"
# UTF-8 without BOM: the repo path contains non-ASCII characters and an ASCII
# write would turn them into '?' and break the path settings.
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($cfg, $lines, $utf8)

Write-Host "starting elasticsearch (heap 512m, single node, security off - LAB ONLY)"
& (Join-Path $EsHome "bin\elasticsearch.bat")
