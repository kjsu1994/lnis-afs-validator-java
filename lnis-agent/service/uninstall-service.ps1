$ErrorActionPreference = 'Stop'
$serviceExe = Join-Path $PSScriptRoot 'lnis-agent-service.exe'
if (-not (Test-Path -LiteralPath $serviceExe)) { throw 'lnis-agent-service.exe was not found.' }
& $serviceExe stop
& $serviceExe uninstall

