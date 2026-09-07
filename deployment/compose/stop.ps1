$ErrorActionPreference = 'Stop'
$composeRoot = $PSScriptRoot
if ($composeRoot -notmatch '^[A-Za-z]:\\') {
    throw 'LNIS 운영 폴더는 Windows 로컬 드라이브에 두세요.'
}

$drive = $composeRoot.Substring(0, 1).ToLowerInvariant()
$relative = $composeRoot.Substring(3).Replace('\', '/')
$linuxRoot = "/mnt/$drive/$relative"

Push-Location $env:SystemRoot
try {
    & wsl.exe --cd $linuxRoot -- docker compose down
    if ($LASTEXITCODE -ne 0) {
        throw 'LNIS 서버 종료에 실패했습니다.'
    }
} finally {
    Pop-Location
}

Write-Host 'LNIS 서버를 종료했습니다.' -ForegroundColor Green
