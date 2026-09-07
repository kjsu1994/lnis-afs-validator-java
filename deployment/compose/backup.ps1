$ErrorActionPreference = 'Stop'
$composeRoot = $PSScriptRoot
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupRoot = Join-Path $composeRoot "backups\$timestamp"
$database = Join-Path $composeRoot 'DB'

if (-not (Test-Path -LiteralPath $database -PathType Container)) {
    throw 'DB 폴더가 없습니다.'
}

# 실행 중인 H2 파일 복사를 피하기 위해 서버를 내린 상태에서만 콜드 백업한다.
& (Join-Path $composeRoot 'stop.ps1')
try {
    New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
    Copy-Item -LiteralPath $database -Destination $backupRoot -Recurse
    Copy-Item -LiteralPath (Join-Path $composeRoot '.env') -Destination $backupRoot
    Write-Host "백업 완료: $backupRoot" -ForegroundColor Green
} finally {
    & (Join-Path $composeRoot 'start.ps1') -NoBrowser
}
