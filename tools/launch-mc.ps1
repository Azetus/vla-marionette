param(
    [string]$World = "",
    [string]$RunDir = "",
    [string]$Jar = "",
    [int]$Port = 0,
    [string]$WorldSource = "",
    [string]$Fov = "",
    [string]$FovMode = "",
    [switch]$NoDeploy,
    [switch]$Installed
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$localConfig = Join-Path $PSScriptRoot 'mc.local.ps1'
if (Test-Path $localConfig) { . $localConfig }

if (-not $env:VLA_MC_VERSION_DIR) {
    throw 'VLA_MC_VERSION_DIR is required; copy tools/mc.local.example.ps1 and set it'
}
$versionDir = (Resolve-Path $env:VLA_MC_VERSION_DIR).Path
if (-not $World) { $World = if ($env:VLA_MC_WORLD) { $env:VLA_MC_WORLD } else { 'vla-m1' } }
if ($Port -eq 0) { $Port = if ($env:VLA_MC_PORT) { [int]$env:VLA_MC_PORT } else { 25580 } }

# Launch-time FOV override (test/experiment convenience). The TOML config stays
# the device contract; these become -Dvla.fov.* system properties.
if ($FovMode -and $FovMode -notin @('fixed', 'vanilla')) {
    throw "FovMode must be 'fixed' or 'vanilla', got '$FovMode'"
}
if ($Fov -and -not $FovMode) { $FovMode = 'fixed' }
if ($Fov) {
    $fovValue = 0.0
    if (-not [double]::TryParse($Fov, [ref]$fovValue) -or
            -not [double]::IsFinite($fovValue) -or
            $fovValue -lt 30 -or $fovValue -gt 110) {
        throw "Fov must be a number in 30..110, got '$Fov'"
    }
    if ($FovMode -ne 'fixed') { throw "Fov requires FovMode 'fixed'" }
}
if (-not $Installed -and -not $WorldSource) {
    $WorldSource = if ($env:VLA_MC_WORLD_SOURCE) { $env:VLA_MC_WORLD_SOURCE } else { Join-Path $versionDir "saves\$World" }
}
if (-not $Installed -and -not (Test-Path $WorldSource)) {
    throw "world source not found: $WorldSource; create a new '$World' world or set VLA_MC_WORLD_SOURCE"
}

$versionId = Split-Path $versionDir -Leaf
$versionJson = Join-Path $versionDir "$versionId.json"
if (-not (Test-Path $versionJson)) { throw "version JSON not found: $versionJson" }

$java = if ($env:VLA_MC_JAVA) { $env:VLA_MC_JAVA } else { (& mise which java).Trim() }
if (-not (Test-Path $java)) { throw "java not found: $java; install Java 25 with mise" }
$javaVersion = (& $java -version 2>&1 | Out-String)
if ($javaVersion -notmatch 'version "25\.') {
    throw "Java 25 required, got:`n$javaVersion"
}

if (-not $Installed) {
    if (-not $Jar) {
        # Build output is versioned; isolated runs copy it under the same name.
        $propsFile = Join-Path $workspace 'gradle.properties'
        if (-not (Test-Path $propsFile)) { throw "gradle.properties not found: $propsFile" }
        $props = ConvertFrom-StringData (Get-Content $propsFile -Raw)
        $Jar = Join-Path $workspace "build\libs\vla-marionette-$($props.minecraft_version)-neoforge-$($props.mod_version).jar"
    }
    if (-not (Test-Path $Jar)) { throw "mod jar not found: $Jar; run tools/build.ps1 first" }
    $Jar = (Resolve-Path $Jar).Path
} else {
    $installedJars = @(Get-ChildItem (Join-Path $versionDir 'mods') -Filter 'vla-marionette*.jar' -File -ErrorAction SilentlyContinue)
    if ($installedJars.Count -ne 1) {
        throw "expected exactly one vla-marionette jar in mods, found $($installedJars.Count); run tools/deploy.ps1 first"
    }
}

$activeFile = Join-Path $workspace 'runs\active.json'
if (Test-Path $activeFile) {
    try {
        $active = Get-Content $activeFile -Raw | ConvertFrom-Json
        $activeProc = Get-CimInstance Win32_Process -Filter "ProcessId=$($active.game_pid)" -ErrorAction SilentlyContinue
        if ($activeProc -and ([string]$activeProc.CommandLine) -match [regex]::Escape([string]$active.run_id)) {
            throw "a Minecraft client is already running (pid $($active.game_pid), run $($active.run_id)); stop it first"
        }
    } catch {
        if ($_.Exception.Message -like 'a Minecraft client is already running*') { throw }
    }
    Remove-Item $activeFile -Force -ErrorAction SilentlyContinue
}

if (-not $RunDir) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $RunDir = Join-Path $workspace "runs\mc-$stamp"
}
New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
$RunDir = (Resolve-Path $RunDir).Path

if ($Installed) {
    $gameDir = $versionDir
    if (-not (Test-Path (Join-Path $gameDir "saves\$World"))) {
        throw "installed world not found: $(Join-Path $gameDir "saves\$World")"
    }
} else {
    $gameDir = Join-Path $RunDir 'game'
    New-Item -ItemType Directory -Force -Path $gameDir,(Join-Path $gameDir 'mods'),(Join-Path $gameDir 'saves'),(Join-Path $gameDir 'logs') | Out-Null

    $optionsFile = Join-Path $gameDir 'options.txt'
    if (-not (Test-Path $optionsFile)) {
        @'
pauseOnLostFocus:false
inactivityFpsLimit:minimized
onboardAccessibility:false
maxFps:120
enableVsync:true
'@ | Set-Content $optionsFile -Encoding utf8
    }

    $saveTarget = Join-Path $gameDir "saves\$World"
    if (Test-Path $saveTarget) { Remove-Item $saveTarget -Recurse -Force }
    Copy-Item $WorldSource $saveTarget -Recurse -Force
    Remove-Item (Join-Path $saveTarget 'session.lock') -Force -ErrorAction SilentlyContinue

    if (-not $NoDeploy) {
        $modsDir = Join-Path $gameDir 'mods'
        Get-ChildItem $modsDir -Filter 'vla-marionette*' -File -ErrorAction SilentlyContinue | Remove-Item -Force
        Copy-Item $Jar (Join-Path $modsDir (Split-Path $Jar -Leaf)) -Force
    }
}

$runId = "vla-$([guid]::NewGuid().ToString('N').Substring(0,12))"
$argvFile = Join-Path $RunDir 'argv.json'
$argvArgs = @(
    (Join-Path $workspace 'tools\mc-argv.mjs'),
    '--version-dir', $versionDir,
    '--game-dir', $gameDir,
    '--world', $World,
    '--port', [string]$Port,
    '--run-id', $runId,
    '--out', $argvFile
)
if ($env:VLA_MC_ASSETS_DIR) { $argvArgs += @('--assets-dir', $env:VLA_MC_ASSETS_DIR) }
if ($env:VLA_MC_LIBRARIES_DIR) { $argvArgs += @('--libraries-dir', $env:VLA_MC_LIBRARIES_DIR) }
if ($FovMode) { $argvArgs += @('--fov-mode', $FovMode) }
if ($Fov) { $argvArgs += @('--fov', $Fov) }
& node @argvArgs
if ($LASTEXITCODE -ne 0) { throw 'mc-argv failed' }

$argv = Get-Content $argvFile -Raw | ConvertFrom-Json
$stdout = Join-Path $RunDir 'stdout.log'
$stderr = Join-Path $RunDir 'stderr.log'
$process = Start-Process -FilePath $java -ArgumentList $argv.args -WorkingDirectory $gameDir `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
Start-Sleep -Milliseconds 500
if ($process.HasExited) {
    $tail = if (Test-Path $stderr) { Get-Content $stderr -Tail 20 | Out-String } else { '' }
    throw "Minecraft exited immediately with code $($process.ExitCode)`n$tail"
}

$launch = [ordered]@{
    run_id = $runId
    game_pid = $process.Id
    launcher_pid = $PID
    java = $java
    version_dir = $versionDir
    version_id = $versionId
    game_dir = $gameDir
    run_dir = $RunDir
    world = $World
    port = $Port
    fov = $Fov
    fov_mode = $FovMode
    started_at = (Get-Date).ToString('o')
    argv_file = $argvFile
    stdout = $stdout
    stderr = $stderr
    log = (Join-Path $gameDir 'logs\latest.log')
}
$launch | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $RunDir 'launch.json') -Encoding utf8
$launch | ConvertTo-Json -Depth 4 | Set-Content $activeFile -Encoding utf8
Write-Host "[launch] pid=$($process.Id) run=$RunDir world=$World port=$Port"
Write-Host "[launch] game_dir=$gameDir"
