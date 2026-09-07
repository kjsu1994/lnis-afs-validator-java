[CmdletBinding()]
param(
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new()
$OutputEncoding = [Text.UTF8Encoding]::new()
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

$agentZip = Join-Path $composeRoot 'agent\lnis-agent-windows.zip'
$senderRoot = Join-Path $composeRoot 'sender-agent'
$packageMarker = Join-Path $senderRoot '.package.sha256'
if (-not (Test-Path -LiteralPath $agentZip -PathType Leaf)) {
    throw 'Agent ZIP이 없습니다. gradlew.bat build를 다시 실행하세요.'
}
$packageHash = (Get-FileHash -LiteralPath $agentZip -Algorithm SHA256).Hash
$installedHash = if (Test-Path -LiteralPath $packageMarker) {
    (Get-Content -LiteralPath $packageMarker -Raw).Trim()
} else {
    ''
}

if ($installedHash -ne $packageHash) {
    # 실행 중인 JAR을 덮어쓰지 않도록 기존 Sender를 먼저 종료하고 새 ZIP을 반영한다.
    $oldStop = Join-Path $senderRoot 'stop-agent.ps1'
    if (Test-Path -LiteralPath $oldStop -PathType Leaf) {
        & $oldStop -Role sender
    }
    $updateRoot = Join-Path $composeRoot '.sender-agent-update'
    if (Test-Path -LiteralPath $updateRoot) {
        Remove-Item -LiteralPath $updateRoot -Recurse -Force
    }
    Expand-Archive -LiteralPath $agentZip -DestinationPath $updateRoot
    New-Item -ItemType Directory -Path $senderRoot -Force | Out-Null

    # 사용자가 만든 역할별 설정과 기존 로그는 유지하고 실행 파일만 갱신한다.
    Get-ChildItem -LiteralPath $updateRoot -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $senderRoot -Recurse -Force
    }
    [IO.File]::WriteAllText($packageMarker, $packageHash)
    Remove-Item -LiteralPath $updateRoot -Recurse -Force
}
$senderAgent = Join-Path $senderRoot 'start-agent.ps1'

# 중앙 서버와 같은 PC의 Sender는 사용자가 별도로 실행하지 않아도 함께 시작한다.
$tokenLine = Get-Content -LiteralPath (Join-Path $composeRoot '.env') |
    Where-Object { $_ -match '^\s*LNIS_AGENT_TOKENS=' } |
    Select-Object -First 1
$senderToken = $null
if ($tokenLine) {
    $configuredTokens = ($tokenLine -split '=', 2)[1]
    foreach ($item in ($configuredTokens -split ',')) {
        $pair = $item.Trim() -split '=', 2
        if ($pair.Count -eq 2 -and $pair[0] -eq 'sender-1') {
            $senderToken = $pair[1]
            break
        }
    }
}
if (-not $senderToken) {
    throw '.env의 LNIS_AGENT_TOKENS에 sender-1 token이 없습니다.'
}

& $senderAgent -Role sender -ServerHost localhost -Token $senderToken

Write-Host ''
Write-Host 'LNIS 서버와 Sender Agent가 정상 실행되었습니다.' -ForegroundColor Green
Write-Host '화면: http://localhost:8088/lnis/afstest/sender'
Write-Host '종료: STOP.cmd'

if (-not $NoBrowser) {
    Start-Process 'http://localhost:8088/lnis/afstest/sender'
}
