$ErrorActionPreference = 'Stop'

# 이 배포본의 Java 실행 파일과 LNIS Main class를 함께 가진 프로세스만 종료한다.
$agentHome = [IO.Path]::GetFullPath($PSScriptRoot)
$bundledJava = [IO.Path]::GetFullPath(
    (Join-Path $agentHome 'runtime\jdk-21\bin\java.exe'))
$agents = Get-CimInstance Win32_Process |
    Where-Object {
        $_.ExecutablePath -and
        [IO.Path]::GetFullPath($_.ExecutablePath) -eq $bundledJava -and
        $_.CommandLine -match 'server\.co\.lnis\.agent\.LnisAgentApplication'
    }

if (-not $agents) {
    Write-Host '이 배포본에서 실행 중인 LNIS Agent가 없습니다.'
    exit 0
}

foreach ($agent in $agents) {
    Stop-Process -Id $agent.ProcessId
    Write-Host "LNIS Agent 종료 완료: PID $($agent.ProcessId)"
}
