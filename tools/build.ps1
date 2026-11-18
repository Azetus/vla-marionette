$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if (-not (Get-Command mise -ErrorAction SilentlyContinue)) {
        throw "mise is not available on PATH. Install mise and run 'mise install' first."
    }
    if (-not $env:GRADLE_USER_HOME) {
        Write-Host "[build] GRADLE_USER_HOME is not set; the wrapper may download Gradle."
    }

    mise install
    if ($LASTEXITCODE -ne 0) {
        throw "mise install failed with exit code $LASTEXITCODE"
    }

    mise exec -- .\gradlew.bat build --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
