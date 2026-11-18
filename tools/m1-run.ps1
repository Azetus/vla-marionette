param(
    [int]$Runs = 3,
    [string]$RunDir = "",
    [string]$World = "",
    [int]$Port = 0,
    [string]$Jar = "",
    [double]$MaxDy = 0.20
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($Runs -lt 1) { throw 'Runs must be >= 1' }

if (-not $RunDir) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $RunDir = Join-Path $workspace "runs\m1-$stamp"
}
New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
$RunDir = (Resolve-Path $RunDir).Path

$launchArgs = @{
    RunDir = $RunDir
}
if ($World) { $launchArgs.World = $World }
if ($Port) { $launchArgs.Port = $Port }
if ($Jar) { $launchArgs.Jar = $Jar }

& (Join-Path $PSScriptRoot 'launch-mc.ps1') @launchArgs
if ($LASTEXITCODE -ne 0) { throw 'launch failed' }
$launch = Get-Content (Join-Path $RunDir 'launch.json') -Raw | ConvertFrom-Json
$port = if ($Port) { $Port } else { [int]$launch.port }

$results = @()
$passed = 0
try {
    for ($i = 1; $i -le $Runs; $i++) {
        if ($i -gt 1) { Start-Sleep -Seconds 2 }
        $out = Join-Path $RunDir ("run{0}" -f $i)
        $acceptArgs = @(
            (Join-Path $workspace 'tools\m1-acceptance.mjs'),
            '--port', [string]$port,
            '--out', $out,
            '--timeout', '60000',
            '--max-dy', [string]$MaxDy
        )
        if ($i -eq $Runs) { $acceptArgs += '--shutdown' }
        & node @acceptArgs
        $code = $LASTEXITCODE
        $reportPath = Join-Path $out 'report.json'
        $runPassed = $false
        if (Test-Path $reportPath) {
            $report = Get-Content $reportPath -Raw | ConvertFrom-Json
            $runPassed = $report.passed -eq $true
        }
        if ($runPassed) { $passed++ }
        $results += [ordered]@{
            run = $i
            exit_code = $code
            passed = $runPassed
            report = $reportPath
        }
        Write-Host ("[m1-run] run {0}/{1} passed={2} exit={3}" -f $i, $Runs, $runPassed, $code)
    }
} finally {
    try {
        & (Join-Path $PSScriptRoot 'stop-mc.ps1') -RunDir $RunDir -TimeoutSec 20 -Force
    } catch {
        Write-Warning "[m1-run] stop fallback: $($_.Exception.Message)"
    }
}

$summary = [ordered]@{
    runs = $Runs
    passed = $passed
    all_passed = ($passed -eq $Runs)
    max_dy = $MaxDy
    launch = $launch
    results = $results
    completed_at = (Get-Date).ToString('o')
}
$summary | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $RunDir 'summary.json') -Encoding utf8
Write-Host ("[m1-run] {0}/{1} passed; summary={2}" -f $passed, $Runs, (Join-Path $RunDir 'summary.json'))
if ($passed -ne $Runs) { exit 2 }
