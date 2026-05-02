param(
    [string]$HostAddress = "127.0.0.1",
    [int]$Port = 8099
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$python = Join-Path $scriptDir ".venv\Scripts\python.exe"

if (-not (Test-Path -LiteralPath $python)) {
    throw "Virtual environment not found at $python. Create it and run: pip install -e ."
}

Set-Location $scriptDir
$env:EXECUTOR_DRY_RUN = "false"

Write-Host "Starting voktrader executor in LIVE mode on http://${HostAddress}:$Port"
Write-Host "Live mode will use Polymarket credentials and local brakes from .env / environment."
& $python -m uvicorn voktrader_executor.main:app --host $HostAddress --port $Port
