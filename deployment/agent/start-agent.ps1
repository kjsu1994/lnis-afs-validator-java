[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('sender', 'receiver')]
    [string]$Role,
    [string]$ServerHost,
    [string]$Token
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new()
$OutputEncoding = [Text.UTF8Encoding]::new()
$agentRoot = $PSScriptRoot
$java = Join-Path $agentRoot 'runtime\bin\java.exe'
$jar = Join-Path $agentRoot 'lnis.jar'
$native = Join-Path $agentRoot 'native'
$configDirectory = Join-Path $agentRoot 'config'
$profileConfig = Join-Path $configDirectory "application-$Role.yml"
$exampleConfig = "$profileConfig.example"
$runDirectory = Join-Path $agentRoot 'run'
$logDirectory = Join-Path $agentRoot 'logs'
$pidFile = Join-Path $runDirectory "$Role.pid"

foreach ($required in @($java, $jar, (Join-Path $native 'LnisAfsCodec.dll'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Agent 실행 파일이 없습니다: $required"
    }
}

if (-not (Test-Path -LiteralPath $profileConfig -PathType Leaf)) {
    if (-not (Test-Path -LiteralPath $exampleConfig -PathType Leaf)) {
        throw "Agent 설정 예제가 없습니다: $exampleConfig"
    }
    Copy-Item -LiteralPath $exampleConfig -Destination $profileConfig
}

New-Item -ItemType Directory -Path $runDirectory, $logDirectory -Force | Out-Null

if (Test-Path -LiteralPath $pidFile) {
    $existingPid = 0
    if ([int]::TryParse((Get-Content -LiteralPath $pidFile -Raw).Trim(), [ref]$existingPid)) {
        $existing = Get-CimInstance Win32_Process -Filter "ProcessId=$existingPid" `
            -ErrorAction SilentlyContinue
        if ($existing -and $existing.CommandLine -like "*$jar*" `
                -and $existing.CommandLine -match "\s$Role(\s|$)") {
            Write-Host "LNIS $Role Agent가 이미 실행 중입니다. PID=$existingPid" `
                -ForegroundColor Green
            return
        }
    }
    Remove-Item -LiteralPath $pidFile -Force
}

$javaArguments = @(
    '-jar',
    $jar,
    $Role,
    '--spring.config.additional-location=optional:file:./config/'
)
if ($ServerHost) {
    $javaArguments += "--lnis.server.ws=ws://${ServerHost}:8088/lnis/agent/ws"
}
if ($Token) {
    $javaArguments += "--lnis.agent.token=$Token"
}
$javaArguments += "--lnis.native.dir=$native"

$process = Start-Process `
    -FilePath $java `
    -WorkingDirectory $agentRoot `
    -ArgumentList $javaArguments `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDirectory "$Role.out.log") `
    -RedirectStandardError (Join-Path $logDirectory "$Role.err.log") `
    -PassThru

[IO.File]::WriteAllText($pidFile, $process.Id.ToString())
Start-Sleep -Seconds 3
$process.Refresh()
if ($process.HasExited) {
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    Get-Content -LiteralPath (Join-Path $logDirectory "$Role.err.log") `
        -Tail 50 -ErrorAction SilentlyContinue
    throw "LNIS $Role Agent가 시작 직후 종료됐습니다."
}

Write-Host "LNIS $Role Agent 실행 완료. PID=$($process.Id)" -ForegroundColor Green
Write-Host "로그: $logDirectory"
