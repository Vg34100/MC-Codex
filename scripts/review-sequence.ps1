# Small foreground-checked batches for repeated navigation in an owned Minecraft review save.
param(
    [Parameter(Mandatory=$true)][int]$MinecraftPid,
    [Parameter(Mandatory=$true)][string]$SequenceFile
)
$ErrorActionPreference = 'Stop'
$steps = Get-Content -LiteralPath $SequenceFile -Raw | ConvertFrom-Json
if ($steps.Count -gt 24) { throw 'Use batches of at most24 actions, observing the screen between batches.' }
$helper = Join-Path $PSScriptRoot 'client-window.ps1'
foreach ($step in $steps) {
    if ($step.action -notin @('click','key','hover','wheel','hold','screenshot','command','resize')) { throw 'Unsupported review action' }
    $arguments = @{ MinecraftPid = $MinecraftPid; Action = [string]$step.action }
    foreach ($property in @('X','Y','Button','Value','OutputPath')) {
        if ($null -ne $step.PSObject.Properties[$property]) { $arguments[$property] = $step.$property }
    }
    & $helper @arguments
    Start-Sleep -Milliseconds 150
}
Write-Output "Completed $($steps.Count) scoped review actions."
