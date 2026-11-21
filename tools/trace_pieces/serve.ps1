# Serves the repo so Trace pieces can save straight into a scene folder.
# Chrome and Edge only allow folder save from http://localhost, not from a file:// page.

$ErrorActionPreference = "Stop"
Set-Location (Resolve-Path (Join-Path $PSScriptRoot "..\.."))

$port = 8765
$url = "http://127.0.0.1:$port/tools/trace_pieces/"
Write-Host "Trace pieces: $url"
Write-Host "Leave this window open. Save writes into the scene folder you pick."
Start-Process $url

if (Get-Command py -ErrorAction SilentlyContinue) {
    py -m http.server $port --bind 127.0.0.1
} else {
    python -m http.server $port --bind 127.0.0.1
}
