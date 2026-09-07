[CmdletBinding()]
param(
    [ValidateSet('sender', 'receiver', 'all')]
    [string]$Role = 'all'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new()
$OutputEncoding = [Text.UTF8Encoding]::new()
$agentRoot = $PSScriptRoot
$runDirectory = Join-Path $agentRoot 'run'
$roles = if ($Role -eq 'all') { @('sender', 'receiver') } else { @($Role) }

foreach ($currentRole in $roles) {
    $pidFile = Join-Path $runDirectory "$currentRole.pid"
    if (-not (Test-Path -LiteralPath $pidFile)) {
        continue
    }

    $processId = 0
    if ([int]::TryParse((Get-Content -LiteralPath $pidFile -Raw).Trim(), [ref]$processId)) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" `
            -ErrorAction SilentlyContinue
        $jar = Join-Path $agentRoot 'lnis.jar'
        if ($process -and $process.CommandLine -like "*$jar*" `
                -and $process.CommandLine -match "\s$currentRole(\s|$)") {
            Stop-Process -Id $processId -Force
            Write-Host "LNIS $currentRole Agent를 종료했습니다. PID=$processId" `
                -ForegroundColor Green
        }
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
}
