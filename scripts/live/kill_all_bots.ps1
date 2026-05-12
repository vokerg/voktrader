param(
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"

$response = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/bots/kill-all"

$response | Format-Table id, name, enabled, runtimeActive, marketFamily, strategyId, strategySetId, subStrategyId, status -AutoSize

