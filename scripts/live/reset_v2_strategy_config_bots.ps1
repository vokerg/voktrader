param(
    [string]$BaseUrl = "http://localhost:8080",
    [string[]]$StrategyConfigIds = @("paper", "deep-research"),
    [string[]]$MarketFamilies = @("BTC_5M", "ETH_5M", "SOL_5M")
)

$ErrorActionPreference = "Stop"

Write-Host "Pausing all existing bots..."
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/bots/kill-all" | Out-Null

Write-Host "Creating/updating Strategy V2 config-level bots..."
& "$PSScriptRoot\enable_v2_strategy_config_bots.ps1" `
    -BaseUrl $BaseUrl `
    -StrategyConfigIds $StrategyConfigIds `
    -MarketFamilies $MarketFamilies `
    -Enabled $true
