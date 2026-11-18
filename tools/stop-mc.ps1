param(
    [string]$RunDir = "",
    [Alias('Pid','GamePid')] [int]$ProcessId = 0,
    [int]$TimeoutSec = 20,
    [switch]$Force,
    [switch]$NoWindowClose
)

$ErrorActionPreference = 'Stop'

function Get-LaunchFile {
    param([string]$Dir)
    if ($Dir) {
        $candidate = Join-Path $Dir 'launch.json'
        if (-not (Test-Path $candidate)) { throw "launch.json not found: $candidate" }
        return (Resolve-Path $candidate).Path
    }
    $latest = Get-ChildItem (Join-Path $PSScriptRoot '..\runs') -Directory -Filter 'mc-*' -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'launch.json' } |
        Where-Object { Test-Path $_ } |
        Sort-Object { (Get-Item $_).LastWriteTime } -Descending |
        Select-Object -First 1
    if (-not $latest) { throw 'no runs/mc-*/launch.json found; pass -RunDir or -ProcessId' }
    return $latest
}

$launch = $null
$runDirPath = $null
if ($ProcessId -eq 0) {
    $launchFile = Get-LaunchFile -Dir $RunDir
    $launch = Get-Content $launchFile -Raw | ConvertFrom-Json
    $ProcessId = [int]$launch.game_pid
    $runDirPath = Split-Path $launchFile -Parent
} elseif ($RunDir) {
    $runDirPath = (Resolve-Path $RunDir).Path
    $launchFile = Join-Path $runDirPath 'launch.json'
    if (Test-Path $launchFile) { $launch = Get-Content $launchFile -Raw | ConvertFrom-Json }
}

$proc = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
if (-not $proc) {
    Write-Host "[stop] pid $ProcessId is already gone"
    if ($runDirPath) {
        Remove-Item (Join-Path $runDirPath 'active.json') -Force -ErrorAction SilentlyContinue
    }
    exit 0
}

$cmd = [string]$proc.CommandLine
if ($cmd -notmatch 'net\.neoforged\.fml\.startup\.Client|net\.minecraft\.client\.main\.Main') {
    throw "refusing to stop pid ${ProcessId}: command line does not look like a Minecraft client"
}
if ($launch -and $launch.run_id -and $cmd -notmatch [regex]::Escape([string]$launch.run_id)) {
    throw "refusing to stop pid ${ProcessId}: launch run_id mismatch"
}

Write-Host "[stop] pid=$ProcessId run=$runDirPath"

if (-not $NoWindowClose) {
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($process) {
        $handle = $process.MainWindowHandle
        $deadline = (Get-Date).AddSeconds(5)
        while ($handle -eq 0 -and (Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 200
            $process.Refresh()
            $handle = $process.MainWindowHandle
        }
        if ($handle -ne 0) {
            Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class VlaWin32 {
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);
}
"@ -ErrorAction SilentlyContinue
            $sent = [VlaWin32]::PostMessage([IntPtr]$handle, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero)
            Write-Host "[stop] WM_CLOSE sent=$sent handle=$handle"
        } else {
            Write-Warning "[stop] no main window handle; skipping WM_CLOSE"
        }
    }

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
            Write-Host "[stop] exited gracefully"
            if ($runDirPath) {
                Remove-Item (Join-Path $runDirPath 'active.json') -Force -ErrorAction SilentlyContinue
            }
            exit 0
        }
        Start-Sleep -Milliseconds 500
    }
    Write-Warning "[stop] graceful close timed out after ${TimeoutSec}s"
}

if (-not $Force) {
    Write-Error "[stop] pid $ProcessId still running; rerun with -Force to terminate the process tree"
    exit 2
}

Write-Host "[stop] forcing process tree pid=$ProcessId"
& taskkill.exe /PID $ProcessId /T /F | Out-Host
Start-Sleep -Milliseconds 500
if (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) {
    Write-Error "[stop] pid $ProcessId is still alive"
    exit 3
}
if ($runDirPath) {
    Remove-Item (Join-Path $runDirPath 'active.json') -Force -ErrorAction SilentlyContinue
}
Write-Host "[stop] stopped pid=$ProcessId"

