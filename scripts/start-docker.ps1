[CmdletBinding()]
param(
    [string]$Distribution = 'Ubuntu',
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$driveName = (Get-Item $projectRoot).PSDrive.Name
$mountPoint = '/mnt/' + $driveName.ToLowerInvariant()
$relativePath = $projectRoot.Substring(3).Replace('\', '/')
$linuxProjectRoot = $mountPoint + '/' + $relativePath

function Invoke-Wsl {
    param([Parameter(Mandatory)][string[]]$Arguments)

    & wsl.exe -d $Distribution @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw 'WSL 명령이 실패했습니다.'
    }
}

# 현재 경로가 미마운트 O:이면 wsl.exe 경로 변환도 실패하므로 로컬 경로에서 호출한다.
Push-Location $env:SystemRoot
try {
    Invoke-Wsl -Arguments @('-u', 'root', '--', 'mkdir', '-p', $mountPoint)

    & wsl.exe -d $Distribution -- mountpoint -q $mountPoint
    if ($LASTEXITCODE -ne 0) {
        Invoke-Wsl -Arguments @(
            '-u', 'root', '--', 'mount', '-t', 'drvfs', ($driveName + ':'), $mountPoint)
    }

    # 빈 WSL 디렉터리를 실제 보안 드라이브로 오인하지 않도록 프로젝트 파일을 확인한다.
    Invoke-Wsl -Arguments @('--', 'test', '-f', ($linuxProjectRoot + '/docker-compose.yml'))

    $composeArguments = @('--cd', $linuxProjectRoot, '--', 'docker', 'compose', 'up', '-d')
    if ($Build) {
        $composeArguments += '--build'
    }
    $composeArguments += '--remove-orphans'
    Invoke-Wsl -Arguments $composeArguments
    Invoke-Wsl -Arguments @('--cd', $linuxProjectRoot, '--', 'docker', 'compose', 'ps')
} finally {
    Pop-Location
}
