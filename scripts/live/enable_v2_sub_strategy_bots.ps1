param(
    [string]$BaseUrl = "http://localhost:8080",
    [string[]]$strategySetIds = @("paper", "deep-research"),
    [string[]]$MarketFamilies = @("BTC_5M", "ETH_5M", "SOL_5M"),
    [bool]$Enabled = $true
)

# Compatibility wrapper kept under the old name. It now creates Strategy V2
# config-level bots, not one bot per inner sub-strategy.

$ErrorActionPreference = "Stop"

$created = @()

foreach ($strategySetId in $strategySetIds) {
    foreach ($marketFamily in $MarketFamilies) {
        $name = "live-v2-$($strategySetId.ToLowerInvariant().Replace('_', '-'))-$($marketFamily.ToLowerInvariant().Replace('_', '-'))"
        $body = @{
            name = $name
            marketFamily = $marketFamily
            strategyId = "strategy-v2"
            strategySetId = $strategySetId
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

$created | Format-Table id, name, enabled, runtimeActive, marketFamily, strategyId, strategySetId, subStrategyId, status -AutoSize

$ids = ($created | ForEach-Object { $_.id }) -join ","
Write-Host ""
Write-Host "For the live-test profile include guard, use:"
Write-Host "voktrader.bots.include-ids=$ids"

