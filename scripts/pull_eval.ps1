# Pull eval result json files from device internal storage to PC
# Usage: ./scripts/pull_eval.ps1

$ErrorActionPreference = "Stop"

$package = "com.mystar.agent"
$remoteDir = "files/eval"
$localDir = Join-Path $PSScriptRoot "..\docs\evaluation\result\device\files\eval"

if (-not (Test-Path $localDir)) {
    New-Item -ItemType Directory -Force -Path $localDir | Out-Null
}

Write-Host "Listing $remoteDir on device..."
$listOutput = adb exec-out run-as $package ls $remoteDir 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to list $remoteDir via run-as: $listOutput"
    exit 1
}

$files = $listOutput -split "`r?`n" |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ -and $_ -match '\.json$' }

if (-not $files -or $files.Count -eq 0) {
    Write-Host "No json files to pull."
    exit 0
}

Write-Host "Found $($files.Count) file(s). Starting pull..."

$okCount = 0
$failCount = 0

foreach ($f in $files) {
    $f = $f.Trim()
    $remotePath = "$remoteDir/$f"
    $localPath = Join-Path $localDir $f

    # Use cmd redirect to avoid binary corruption from adb exec-out
    cmd /c "adb exec-out run-as $package cat `"$remotePath`" > `"$localPath`""

    if ((Test-Path $localPath) -and ((Get-Item $localPath).Length -gt 0)) {
        adb shell run-as $package rm $remotePath | Out-Null
        $okCount++
    } else {
        Write-Warning "Pull failed: $f"
        Remove-Item -ErrorAction SilentlyContinue $localPath
        $failCount++
    }
}

Write-Host "Done: $okCount succeeded, $failCount failed"
Write-Host "Saved to: $localDir"
