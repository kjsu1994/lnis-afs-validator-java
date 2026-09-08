param(
    [string]$OpenSourceDirectory = '',
    [string]$Distribution = 'Ubuntu'
)
$ErrorActionPreference = 'Stop'
$nativeRoot = $PSScriptRoot
$projectRoot = Split-Path -Parent $nativeRoot
if ([string]::IsNullOrWhiteSpace($OpenSourceDirectory)) {
    $originalRoot = Join-Path (Split-Path -Parent $projectRoot) 'LnisAfsValidator'
    $OpenSourceDirectory = (Get-ChildItem -LiteralPath $originalRoot -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'PocketSDR-AFS-main') } |
        Select-Object -First 1).FullName
    if (!$OpenSourceDirectory) { throw 'Specify -OpenSourceDirectory.' }
}
$stage = Join-Path ([IO.Path]::GetTempPath()) ('LnisPvtBuild-' + [Guid]::NewGuid().ToString('N'))
# 원본은 읽기만 한다. 빌드 산출물도 검증 전에는 배포 DLL을 덮어쓰지 않는다.
New-Item -ItemType Directory -Path $stage | Out-Null
$lans = Join-Path $OpenSourceDirectory 'LANS-AFS-SIM-main'
$pocket = Join-Path $OpenSourceDirectory 'PocketSDR-AFS-main'
Copy-Item -LiteralPath (Join-Path $pocket 'lib/RTKLIB/src') -Destination (Join-Path $stage 'rtk') -Recurse
Copy-Item -LiteralPath (Join-Path $lans 'afs_nav.c'),(Join-Path $lans 'afs_nav.h') -Destination $stage
Copy-Item -LiteralPath (Join-Path $lans 'ldpc'),(Join-Path $lans 'pocketsdr') -Destination $stage -Recurse
Copy-Item -LiteralPath (Join-Path $pocket 'lib/win32/libsdr.a'),(Join-Path $pocket 'lib/win32/libldpc.a') -Destination $stage
Copy-Item -LiteralPath (Join-Path $nativeRoot 'lnis_afs_codec.c'),(Join-Path $nativeRoot 'lnis_afs_codec.h'),(Join-Path $nativeRoot 'lnis_pvt.c'),(Join-Path $nativeRoot 'lnis_pvt.h'),(Join-Path $nativeRoot 'build-pvt.sh') -Destination $stage
Copy-Item -LiteralPath (Join-Path $nativeRoot 'test_pvt.c') -Destination $stage
# 기존 로컬 LANS 사본의 문법이 손상된 로그 함수만 임시 복사본에서 제외한다.
# 기존 WPF build-wsl.ps1과 같은 예외이며 부호화 알고리즘은 수정하지 않는다.
$source = Join-Path $stage 'afs_nav.c'
$contents = [IO.File]::ReadAllText($source)
$contents = [regex]::Replace($contents, 'static void bits_to_hex\(.*?\r?\n\}', 'static void bits_to_hex(const uint8_t* bits, int len, char* hex) { (void)bits; (void)len; if (hex) hex[0] = 0; }', [Text.RegularExpressions.RegexOptions]::Singleline)
$contents = [regex]::Replace($contents, 'void log_AFS_bits\(.*?\r?\n\}', 'void log_AFS_bits(FILE* fp, const char* id, int prn, int toi, int sb, const char* stage, const uint8_t* bits, int len) { (void)fp; (void)id; (void)prn; (void)toi; (void)sb; (void)stage; (void)bits; (void)len; }', [Text.RegularExpressions.RegexOptions]::Singleline)
[IO.File]::WriteAllText($source, $contents, [Text.UTF8Encoding]::new($false))
$linuxStage = '/mnt/c' + $stage.Substring(2).Replace('\','/')
Push-Location $env:SystemRoot
try {
    wsl.exe -d $Distribution --cd $linuxStage -- bash ./build-pvt.sh
    if ($LASTEXITCODE -ne 0) { throw 'PVT DLL build failed.' }
} finally { Pop-Location }
Push-Location $stage
try {
    & (Join-Path $stage 'test_pvt.exe')
    if ($LASTEXITCODE -ne 0) { throw 'Native PVT synthetic test failed.' }
} finally { Pop-Location }
$output = Join-Path $projectRoot 'build/native-pvt'
New-Item -ItemType Directory -Path $output -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $stage 'LnisAfsCodec.dll') -Destination $output
# 재현성 기록에는 원본 파일 해시를 남긴다. 임시 빌드 폴더는 진단을 위해 유지한다.
Get-ChildItem -LiteralPath (Join-Path $pocket 'lib/RTKLIB/src') -File | Get-FileHash -Algorithm SHA256 | Export-Csv -LiteralPath (Join-Path $output 'upstream-sha256.csv') -NoTypeInformation -Encoding UTF8
Write-Output ('PVT candidate DLL: ' + $output)
Write-Output ('Build staging: ' + $stage)
