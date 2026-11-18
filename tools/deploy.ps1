param(
    [string]$Jar = "",
    [string]$GameDir = "",
    [switch]$NoBackup
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $Jar) { $Jar = Join-Path $workspace 'build\libs\vla-marionette.jar' }
if (-not (Test-Path $Jar)) { throw "jar not found: $Jar" }
$Jar = (Resolve-Path $Jar).Path

if (-not $GameDir) {
    $activeFile = Join-Path $workspace 'runs\active.json'
    if (-not (Test-Path $activeFile)) { throw 'no -GameDir and no runs/active.json' }
    $active = Get-Content $activeFile -Raw | ConvertFrom-Json
    $GameDir = $active.game_dir
}
$GameDir = (Resolve-Path $GameDir).Path
$modsDir = Join-Path $GameDir 'mods'
New-Item -ItemType Directory -Force -Path $modsDir | Out-Null

$target = Join-Path $modsDir 'vla-marionette.jar'
if (Test-Path $target) {
    if (-not $NoBackup) {
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        $backup = Join-Path $modsDir "vla-marionette.bak-$stamp.jar.disabled"
        Copy-Item $target $backup -Force
        Write-Host "[deploy] backup $target -> $backup"
    }
    Remove-Item $target -Force
}

Copy-Item $Jar $target -Force
$count = @(Get-ChildItem $modsDir -Filter 'vla-marionette.jar' -File).Count
if ($count -ne 1) { throw "expected exactly one vla-marionette jar, found $count" }
Write-Host "[deploy] $target"
