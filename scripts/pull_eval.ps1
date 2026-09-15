# Pull eval result json files from device internal storage to PC
# Usage: ./scripts/pull_eval.ps1

$ErrorActionPreference = "Stop"

$package = "com.mystar.agent"
$remoteDir = "files/eval"
$localDir = Join-Path $PSScriptRoot "..\docs\evaluation\result\device\files\eval"

if (-not (Test-Path $localDir)) {
    New-Item -ItemType Directory -Force -Path $localDir | Out-Null
}

# PowerShell 5.1 decodes native command output with the OEM code page (CP949),
# which corrupts UTF-8 Korean filenames from adb and breaks the .json filter.
function Invoke-AdbUtf8 {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$ArgumentList,
        [string]$OutFile
    )
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "adb"
    $psi.Arguments = ($ArgumentList | ForEach-Object {
        if ($_ -match '[\s"]') { '"{0}"' -f ($_ -replace '"', '\"') } else { $_ }
    }) -join " "
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    $p = New-Object System.Diagnostics.Process
    $p.StartInfo = $psi
    [void]$p.Start()
    if ($OutFile) {
        $fs = [System.IO.File]::Create($OutFile)
        try {
            $p.StandardOutput.BaseStream.CopyTo($fs)
        } finally {
            $fs.Close()
        }
        $err = $p.StandardError.ReadToEnd()
        $p.WaitForExit()
        return @{ ExitCode = $p.ExitCode; Err = $err }
    }
    $out = $p.StandardOutput.ReadToEnd()
    $err = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    return @{ ExitCode = $p.ExitCode; Out = $out; Err = $err }
}

Write-Host "Listing $remoteDir on device..."
$list = Invoke-AdbUtf8 -ArgumentList @("exec-out", "run-as", $package, "ls", "-1", $remoteDir)
if ($list.ExitCode -ne 0) {
    Write-Error "Failed to list $remoteDir via run-as: $($list.Err)$($list.Out)"
    exit 1
}

$files = @($list.Out -split '\r?\n' |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ -and $_ -match '\.json$' })

if ($files.Count -eq 0) {
    Write-Host "No json files to pull."
    exit 0
}

Write-Host "Found $($files.Count) file(s). Starting pull..."

$okCount = 0
$failCount = 0

foreach ($f in $files) {
    $remotePath = "$remoteDir/$f"
    $localPath = Join-Path $localDir $f

    $pulled = Invoke-AdbUtf8 -ArgumentList @(
        "exec-out", "run-as", $package, "cat", $remotePath
    ) -OutFile $localPath

    if ($pulled.ExitCode -eq 0 -and (Test-Path -LiteralPath $localPath) -and ((Get-Item -LiteralPath $localPath).Length -gt 0)) {
        $removed = Invoke-AdbUtf8 -ArgumentList @(
            "shell", "run-as", $package, "rm", $remotePath
        )
        if ($removed.ExitCode -ne 0) {
            Write-Warning "Pulled but failed to delete on device: $f"
        }
        $okCount++
    } else {
        Write-Warning "Pull failed: $f"
        Remove-Item -LiteralPath $localPath -ErrorAction SilentlyContinue
        $failCount++
    }
}

Write-Host "Done: $okCount succeeded, $failCount failed"
Write-Host "Saved to: $localDir"
