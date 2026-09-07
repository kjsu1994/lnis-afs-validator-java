[CmdletBinding()]
param(
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
$composeRoot = $PSScriptRoot

if (-not (Test-Path -LiteralPath (Join-Path $composeRoot 'lnis.jar') -PathType Leaf)) {
    throw 'lnis.jar가 없습니다. 개발 PC에서 gradlew.bat build를 먼저 실행하세요.'
}
if (-not (Test-Path -LiteralPath (Join-Path $composeRoot 'docker-compose.yml') -PathType Leaf)) {
    throw 'docker-compose.yml이 없습니다.'
}
if ($composeRoot -notmatch '^[A-Za-z]:\\') {
    throw 'LNIS 운영 폴더는 C: 같은 Windows 로컬 드라이브에 두세요.'
}

$drive = $composeRoot.Substring(0, 1).ToLowerInvariant()
$relative = $composeRoot.Substring(3).Replace('\', '/')
$linuxRoot = "/mnt/$drive/$relative"

if (-not (Test-Path -LiteralPath (Join-Path $composeRoot '.env'))) {
    Copy-Item -LiteralPath (Join-Path $composeRoot '.env.example') `
        -Destination (Join-Path $composeRoot '.env')
    Write-Warning '.env를 기본값으로 만들었습니다. 운영 전 Agent token을 변경하세요.'
}
New-Item -ItemType Directory -Path (Join-Path $composeRoot 'DB') -Force | Out-Null

function Invoke-WslDocker {
    param([Parameter(Mandatory)][string[]]$Arguments)

    # O: 같은 현재 작업 드라이브를 WSL이 해석하지 않도록 Windows 시스템 폴더에서 호출한다.
    Push-Location $env:SystemRoot
    try {
        & wsl.exe --cd $linuxRoot -- docker @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw 'WSL Docker 명령이 실패했습니다. WSL과 Docker 실행 상태를 확인하세요.'
        }
    } finally {
        Pop-Location
    }
}

Invoke-WslDocker -Arguments @('compose', 'up', '-d', '--build', '--remove-orphans')

$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 3
    Push-Location $env:SystemRoot
    try {
        $health = & wsl.exe --cd $linuxRoot -- docker compose ps `
            --format json 2>$null
    } finally {
        Pop-Location
    }
    $healthy = $health -match '"Health":"healthy"' -or $health -match '\(healthy\)'
} while (-not $healthy -and (Get-Date) -lt $deadline)

if (-not $healthy) {
    Invoke-WslDocker -Arguments @('compose', 'logs', '--tail=100', 'server')
    throw 'LNIS 서버가 3분 안에 healthy 상태가 되지 않았습니다.'
}

Write-Host ''
Write-Host 'LNIS 서버가 정상 실행되었습니다.' -ForegroundColor Green
Write-Host '화면: http://localhost:8088/lnis/afstest/sender'
Write-Host '종료: STOP.cmd'

if (-not $NoBrowser) {
    Start-Process 'http://localhost:8088/lnis/afstest/sender'
}
