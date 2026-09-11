# 평가(eval) 결과 json을 기기 내부 저장소에서 PC로 자동 pull
# 사용법: PowerShell에서 ./scripts/pull_eval.ps1

$ErrorActionPreference = "Stop"

$package = "com.mystar.agent"
$remoteDir = "files/eval"
$localDir = Join-Path $PSScriptRoot "..\docs\evaluation\result\device\files\eval"

if (-not (Test-Path $localDir)) {
    New-Item -ItemType Directory -Force -Path $localDir | Out-Null
}

Write-Host "기기에서 $remoteDir 목록 조회 중..."
$listOutput = adb shell run-as $package ls $remoteDir 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Error "run-as로 $remoteDir 목록을 가져오지 못했습니다: $listOutput"
    exit 1
}

$files = $listOutput -split "`r?`n" | Where-Object { $_.Trim() -like "*.json" }

if (-not $files -or $files.Count -eq 0) {
    Write-Host "가져올 json 파일이 없습니다."
    exit 0
}

Write-Host "총 $($files.Count)개 파일 발견. pull 시작..."

$okCount = 0
$failCount = 0

foreach ($f in $files) {
    $f = $f.Trim()
    $remotePath = "$remoteDir/$f"
    $localPath = Join-Path $localDir $f

    # adb exec-out을 cmd 리다이렉션으로 받아 바이너리 손상을 방지한다
    cmd /c "adb exec-out run-as $package cat `"$remotePath`" > `"$localPath`""

    if ((Test-Path $localPath) -and ((Get-Item $localPath).Length -gt 0)) {
        adb shell run-as $package rm $remotePath | Out-Null
        $okCount++
    } else {
        Write-Warning "pull 실패: $f"
        Remove-Item -ErrorAction SilentlyContinue $localPath
        $failCount++
    }
}

Write-Host "완료: 성공 $okCount, 실패 $failCount"
Write-Host "저장 위치: $localDir"
