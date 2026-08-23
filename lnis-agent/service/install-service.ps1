param(
    [Parameter(Mandatory=$true)][ValidateSet('SENDER','RECEIVER')][string]$Role,
    [Parameter(Mandatory=$true)][string]$AgentId,
    [Parameter(Mandatory=$true)][string]$ServerHost,
    [Parameter(Mandatory=$true)][string]$Token,
    [Parameter(Mandatory=$true)][string]$WinSwExe
)
$ErrorActionPreference = 'Stop'
$base = Split-Path -Parent $PSScriptRoot
$bundledJava = Join-Path $base 'runtime\jdk-21\bin\java.exe'
if (-not (Test-Path -LiteralPath $bundledJava)) {
    throw "LNIS 전용 Java 21을 찾을 수 없습니다: $bundledJava"
}
$source = (Resolve-Path -LiteralPath $WinSwExe).Path
$serviceExe = Join-Path $PSScriptRoot 'lnis-agent-service.exe'
Copy-Item -LiteralPath $source -Destination $serviceExe -Force
$properties = @(
    "lnis.agent.id=$AgentId"
    "lnis.agent.role=$Role"
    "lnis.server.ws=ws://$ServerHost`:8088/lnis/agent/ws"
    "lnis.agent.token=$Token"
    "lnis.native.dir=$base\native"
)
Set-Content -LiteralPath (Join-Path $base 'conf\agent.properties') -Value $properties -Encoding UTF8
& $serviceExe install
if ($LASTEXITCODE -ne 0) { throw "WinSW install failed with exit code $LASTEXITCODE" }
& $serviceExe start
if ($LASTEXITCODE -ne 0) { throw "WinSW start failed with exit code $LASTEXITCODE" }
Write-Output "LNIS $Role agent service installed as $AgentId."
