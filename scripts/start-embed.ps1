# Start the embedding sidecar (127.0.0.1:8025). ASCII only.
#
#   .\scripts\start-embed.ps1          start in the foreground
#   .\scripts\start-embed.ps1 -Check   run the offline selfcheck and exit
#
# Needs .runtime/models/multilingual-e5-small/{model.onnx,tokenizer.json}.
# pip is broken on this host; the sidecar only uses already-installed packages.

param(
    [switch]$Check
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$env:PYTHONIOENCODING = "utf-8"
$script = "ai_service\embed_service.py"
if (-not (Test-Path $script)) { throw "missing $script" }

if ($Check) {
    & python $script --selfcheck
    exit $LASTEXITCODE
}

& python $script
