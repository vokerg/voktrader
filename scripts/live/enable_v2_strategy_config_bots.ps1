param(
    [string]$BaseUrl = "http://localhost:8080",
    [string[]]$StrategyConfigIds = @("paper", "deep-research"),
    [string[]]$MarketFamilies = @("BTC_5M", "ETH_5M", "SOL_5M"),
    [bool]$Enabled = $true
)

$ErrorActionPreference = "Stop"

$created = @()

foreach ($strategyConfigId in $StrategyConfigIds) {
    foreach ($marketFamily in $MarketFamilies) {
        $name = "live-v2-$($strategyConfigId.ToLowerInvariant().Replace('_', '-'))-$($marketFamily.ToLowerInvariant().Replace('_', '-'))"
        $body = @{
            name = $name
            marketFamily = $marketFamily
            strategyId = "strategy-v2"
            strategyConfigId = $strategyConfigId
            subStrategyId = ""
            enabled = $Enabled
        } | ConvertTo-Json -Compress

        $created += Invoke-RestMethod `
            -Method Post `
            -Uri "$BaseUrl/api/bots" `
            -ContentType "application/json" `
            -Body $body
    }
}

$created | Format-Table id, name, enabled, runtimeActive, marketFamily, strategyId, strategyConfigId, subStrategyId, status -AutoSize

$ids = ($created | ForEach-Object { $_.id }) -join ","
Write-Host ""
Write-Host "For the live-test profile include guard, use:"
Write-Host "voktrader.bots.include-ids=$ids"
