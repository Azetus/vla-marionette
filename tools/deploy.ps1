param(
    [string]$Jar = "",
    [string]$GameDir = ""
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $Jar) {
    # Build output is versioned; install it under the same name.
    $propsFile = Join-Path $workspace 'gradle.properties'
    if (-not (Test-Path $propsFile)) { throw "gradle.properties not found: $propsFile" }
    $props = ConvertFrom-StringData (Get-Content $propsFile -Raw)
    $Jar = Join-Path $workspace "build\libs\vla-marionette-$($props.minecraft_version)-neoforge-$($props.mod_version).jar"
}
if (-not (Test-Path $Jar)) { throw "jar not found: $Jar; build the project first" }
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

$targetName = Split-Path $Jar -Leaf
if ($targetName -notlike 'vla-marionette*.jar') {
    throw "unexpected jar name: $targetName; expected vla-marionette*.jar"
}

# Do not keep old vla-marionette jars (active or disabled). NeoForge loads every
# jar in mods/, so a leftover version would duplicate the mod id.
Get-ChildItem $modsDir -Filter 'vla-marionette*' -File | Remove-Item -Force

$target = Join-Path $modsDir $targetName
Copy-Item $Jar $target -Force

$activeJars = @(Get-ChildItem $modsDir -Filter 'vla-marionette*.jar' -File)
if ($activeJars.Count -ne 1 -or $activeJars[0].FullName -ne $target) {
    throw "expected exactly one installed vla-marionette jar, found $($activeJars.Count)"
}
Write-Host "[deploy] $target"
