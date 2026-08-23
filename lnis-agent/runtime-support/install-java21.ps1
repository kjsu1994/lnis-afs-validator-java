$ErrorActionPreference = 'Stop'

# 공식 Adoptium API에서 최신 Java 21 Windows x64 JRE를 조회한다.
$runtimeRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$destination = Join-Path $runtimeRoot 'jdk-21'
$javaExecutable = Join-Path $destination 'bin\java.exe'

if (Test-Path -LiteralPath $javaExecutable) {
    Write-Host "LNIS 전용 Java 21이 이미 준비되어 있습니다: $javaExecutable"
    exit 0
}

if (Test-Path -LiteralPath $destination) {
    throw "불완전한 런타임 폴더가 있습니다. 확인 후 직접 정리하세요: $destination"
}

$apiUri = 'https://api.adoptium.net/v3/assets/latest/21/hotspot'
$apiUri += '?architecture=x64&image_type=jre&os=windows&vendor=eclipse'
$asset = Invoke-RestMethod -Uri $apiUri -TimeoutSec 30 | Select-Object -First 1
if ($null -eq $asset) {
    throw 'Adoptium API에서 Windows Java 21 JRE 정보를 찾지 못했습니다.'
}

$temporary = Join-Path $runtimeRoot ('.download-' + [guid]::NewGuid().ToString('N'))
$archive = Join-Path $temporary $asset.binary.package.name
$expanded = Join-Path $temporary 'expanded'

try {
    New-Item -ItemType Directory -Path $expanded -Force | Out-Null
    Write-Host "Java 21 다운로드: $($asset.release_name)"
    Invoke-WebRequest `
        -Uri $asset.binary.package.link `
        -OutFile $archive `
        -TimeoutSec 300

    $actualChecksum = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
    $expectedChecksum = $asset.binary.package.checksum
    if ($actualChecksum -ne $expectedChecksum) {
        throw "Java 21 SHA-256 검증 실패: expected=$expectedChecksum actual=$actualChecksum"
    }

    Expand-Archive -LiteralPath $archive -DestinationPath $expanded
    $source = Get-ChildItem -LiteralPath $expanded -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') } |
        Select-Object -First 1
    if ($null -eq $source) {
        throw '압축 파일에서 Java 실행 파일을 찾지 못했습니다.'
    }

    Move-Item -LiteralPath $source.FullName -Destination $destination
    Write-Host "LNIS 전용 Java 21 준비 완료: $javaExecutable"
} finally {
    $resolvedTemporary = [IO.Path]::GetFullPath($temporary)
    $safeTemporary = $resolvedTemporary.StartsWith($runtimeRoot + [IO.Path]::DirectorySeparatorChar)
    if ($safeTemporary -and (Test-Path -LiteralPath $resolvedTemporary)) {
        Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force
    }
}
