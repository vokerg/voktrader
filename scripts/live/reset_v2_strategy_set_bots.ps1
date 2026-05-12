param(
    [string]$BaseUrl = "http://localhost:8080",
    [string[]]$strategySetIds = @("paper", "deep-research"),
    [string[]]$MarketFamilies = @("BTC_5M", "ETH_5M", "SOL_5M")
)

$ErrorActionPreference = "Stop"

Write-Host "Pausing all existing bots..."
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/bots/kill-all" | Out-Null

Write-Host "Creating/updating Strategy V2 set-level bots..."
& "$PSScriptRoot\enable_v2_strategy_set_bots.ps1" `
    -BaseUrl $BaseUrl `
    -strategySetIds $strategySetIds `
    -MarketFamilies $MarketFamilies `
    -Enabled $true

