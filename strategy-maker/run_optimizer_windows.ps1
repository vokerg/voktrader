# run_optimizer_windows.ps1
# Windows runner for strategy-maker\voktrader_optimizer.py
#
# Put this file next to:
#   strategy-maker\voktrader_optimizer.py
#
# Run from PowerShell:
#   cd C:\repos\voktrader
#   .\strategy-maker\run_optimizer_windows.ps1 -Model qwen3:14b -Iters 20
#
# Or from strategy-maker:
#   .\run_optimizer_windows.ps1 -Model qwen3:14b -Iters 20

param(
    [string]$Profile = $(if ($env:PROFILE) { $env:PROFILE } else { "strategy-v2-paper" }),
    [string]$Model = $(if ($env:MODEL) { $env:MODEL } else { "qwen3:14b" }),
    [int]$Iters = $(if ($env:MAX_ITERS) { [int]$env:MAX_ITERS } else { 20 }),
    [int]$MinTrades = $(if ($env:MIN_TRADES) { [int]$env:MIN_TRADES } else { 5 }),
    [ValidateSet("auto", "manual", "external")]
    [string]$ServerMode = $(if ($env:SERVER_MODE) { $env:SERVER_MODE } else { "auto" }),
    [int]$Port = $(if ($env:APP_PORT) { [int]$env:APP_PORT } else { 8080 }),
    [int]$NumCtx = $(if ($env:NUM_CTX) { [int]$env:NUM_CTX } else { 16384 }),
    [double]$Temperature = $(if ($env:TEMPERATURE) { [double]$env:TEMPERATURE } else { 0.2 }),
    [string]$OllamaUrl = $(if ($env:OLLAMA_URL) { $env:OLLAMA_URL } else { "http://localhost:11434" }),
    [string[]]$MarketIds = $(if ($env:MARKET_IDS) { $env:MARKET_IDS -split '[,\s]+' | Where-Object { $_ } } else { @() }),
    [string]$Repo = "",
    [string]$RepoWin = "",
    [string]$StrategyFile = $(if ($env:STRATEGY_FILE) { $env:STRATEGY_FILE } else { "" }),
    [switch]$ShowOllamaPs,
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$ExtraArgs
)

$ErrorActionPreference = "Stop"

function Resolve-PythonCommand {
    $py = Get-Command py -ErrorAction SilentlyContinue
    if ($py) {
        return @{ Exe = "py"; Args = @("-3") }
    }

    $python = Get-Command python -ErrorAction SilentlyContinue
    if ($python) {
        return @{ Exe = "python"; Args = @() }
    }

    $python3 = Get-Command python3 -ErrorAction SilentlyContinue
    if ($python3) {
        return @{ Exe = "python3"; Args = @() }
    }

    throw "Could not find Python. Install Python, or make sure 'py' or 'python' is on PATH."
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Optimizer = Join-Path $ScriptDir "voktrader_optimizer.py"

if (-not (Test-Path -LiteralPath $Optimizer)) {
    throw "Cannot find optimizer: $Optimizer. Put this runner next to voktrader_optimizer.py."
}

if ([string]::IsNullOrWhiteSpace($Repo)) {
    # Expected layout:
    #   C:\repos\voktrader\strategy-maker\run_optimizer_windows.ps1
    # so repo is parent of strategy-maker.
    $Repo = Split-Path -Parent $ScriptDir
}

$RepoResolved = (Resolve-Path -LiteralPath $Repo).Path

if ([string]::IsNullOrWhiteSpace($RepoWin)) {
    $RepoWin = $RepoResolved
}

if (-not (Test-Path -LiteralPath (Join-Path $RepoResolved "pom.xml"))) {
    throw "Repo path does not look like voktrader: $RepoResolved"
}

Write-Host ""
Write-Host "Voktrader optimizer Windows runner"
Write-Host "Repo:        $RepoResolved"
Write-Host "Optimizer:   $Optimizer"
Write-Host "Profile:     $Profile"
Write-Host "Model:       $Model"
Write-Host "Iters:       $Iters"
Write-Host "Market IDs:  $(if ($MarketIds.Count -gt 0) { $MarketIds -join ', ' } else { '<profile defaults>' })"
Write-Host "Server mode: $ServerMode"
Write-Host "Ollama URL:  $OllamaUrl"
Write-Host ""

try {
    $resp = Invoke-WebRequest -Uri $OllamaUrl -UseBasicParsing -TimeoutSec 5
    if ($resp.Content -notmatch "Ollama") {
        Write-Warning "Ollama endpoint responded, but did not say 'Ollama is running'. Continuing anyway."
    }
} catch {
    throw "Ollama is not reachable at $OllamaUrl. Start it first, for example: ollama serve"
}

if ($ShowOllamaPs -and (Get-Command ollama -ErrorAction SilentlyContinue)) {
    Write-Host "Currently loaded Ollama models:"
    ollama ps
    Write-Host ""
}

$py = Resolve-PythonCommand

$optimizerArgs = @(
    $Optimizer,
    "--repo", $RepoResolved,
    "--repo-win", $RepoWin,
    "--profile", $Profile,
    "--model", $Model,
    "--iters", "$Iters",
    "--min-trades", "$MinTrades",
    "--port", "$Port",
    "--server-mode", $ServerMode,
    "--ollama-url", $OllamaUrl,
    "--num-ctx", "$NumCtx",
    "--temperature", "$Temperature"
)

if (-not [string]::IsNullOrWhiteSpace($StrategyFile)) {
    $optimizerArgs += @("--strategy-file", $StrategyFile)
}

if ($MarketIds -and $MarketIds.Count -gt 0) {
    $optimizerArgs += "--market-ids"
    $optimizerArgs += $MarketIds
}

if ($ExtraArgs -and $ExtraArgs.Count -gt 0) {
    $optimizerArgs += $ExtraArgs
}

Push-Location $RepoResolved
try {
    & $py.Exe @($py.Args + $optimizerArgs)
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

exit $exitCode
