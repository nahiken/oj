# Starts the Hermes worker on loopback (127.0.0.1:8027).
#
# Requirements that are easy to forget:
#   - Python 3.14. Hermes declares every dependency for >=3.14, so 3.11 cannot
#     import it.
#   - A Hermes checkout. It publishes no wheel; the supported dev path is
#     "run from a checkout". The checkout path must be on sys.path, and for the
#     embeddable Python that means the python314._pth file, not PYTHONPATH.
#
# Env knobs: P6_WORKER_PYTHON, P6_WORKER_PORT, P6_AGENT_MODEL,
#            P6_AGENT_BASE_URL, P6_INTERNAL_KEY, DEEPSEEK_API_KEY.

$ErrorActionPreference = 'Stop'

$python = $env:P6_WORKER_PYTHON
if (-not $python) { $python = 'D:\work\environment\py314\python.exe' }
if (-not (Test-Path $python)) {
    Write-Error "Python 3.14 not found at $python. Set P6_WORKER_PYTHON to a 3.14 interpreter."
}

$repo = Split-Path -Parent $PSScriptRoot
$keyFile = Join-Path $repo '.runtime\secrets\deepseek.key'
if (-not $env:DEEPSEEK_API_KEY -and (Test-Path $keyFile)) {
    $env:DEEPSEEK_API_KEY = (Get-Content $keyFile -Raw).Trim()
}
if (-not $env:DEEPSEEK_API_KEY) {
    Write-Error "No model key: set DEEPSEEK_API_KEY or write $keyFile"
}

$internalKeyFile = Join-Path $repo '.runtime\secrets\internal.key'
if (-not $env:P6_INTERNAL_KEY -and (Test-Path $internalKeyFile)) {
    $env:P6_INTERNAL_KEY = (Get-Content $internalKeyFile -Raw).Trim()
}
if (-not $env:P6_INTERNAL_KEY) {
    Write-Error "No internal key: set P6_INTERNAL_KEY or write $internalKeyFile (worker fails closed)."
}

if (-not $env:P6_WORKER_PORT) { $env:P6_WORKER_PORT = '8027' }

Set-Location (Join-Path $repo 'hermes')
Write-Host "starting hermes worker on 127.0.0.1:$($env:P6_WORKER_PORT)"
& $python server.py
