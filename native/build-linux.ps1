param(
    [Parameter(Mandatory = $true)][string]$OpenSourceDirectory,
    [Parameter(Mandatory = $true)][string]$LdpcSourceDirectory,
    [string]$Distribution = 'Ubuntu'
)
$ErrorActionPreference = 'Stop'
$nativeRoot = $PSScriptRoot
$projectRoot = Split-Path -Parent $nativeRoot
$ldpcRevision = '74a8e283be8259dbff7a6bab38ad7e9327825cbf'
$actualRevision = git -C $LdpcSourceDirectory rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $actualRevision -ne $ldpcRevision) {
    throw "LDPC-codes revision must be $ldpcRevision"
}
$dirtyFiles = git -C $LdpcSourceDirectory status --porcelain
if ($LASTEXITCODE -ne 0 -or $dirtyFiles) { throw 'LDPC source must be an unmodified checkout.' }
$stage = Join-Path ([IO.Path]::GetTempPath()) ('LnisLinuxBuild-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
$lans = Join-Path $OpenSourceDirectory 'LANS-AFS-SIM-main'
$pocket = Join-Path $OpenSourceDirectory 'PocketSDR-AFS-main'

# 외부 원본은 읽기만 하고 별도 임시 복사본에서 빌드한다.
Copy-Item -LiteralPath (Join-Path $pocket 'lib/RTKLIB/src') -Destination (Join-Path $stage 'rtk') -Recurse
Copy-Item -LiteralPath (Join-Path $lans 'afs_nav.c'), (Join-Path $lans 'afs_nav.h') -Destination $stage
Copy-Item -LiteralPath (Join-Path $lans 'pocketsdr') -Destination $stage -Recurse
Copy-Item -LiteralPath $LdpcSourceDirectory -Destination (Join-Path $stage 'ldpc') -Recurse
Copy-Item -LiteralPath (Join-Path $pocket 'src/sdr_ldpc_afs.c'), (Join-Path $pocket 'src/pocket_sdr.h') -Destination $stage
foreach ($name in @('lnis_afs_codec.c', 'lnis_afs_codec.h', 'lnis_pvt.c', 'lnis_pvt.h', 'test_pvt.c', 'test_afs.c', 'build-linux.sh')) {
    Copy-Item -LiteralPath (Join-Path $nativeRoot $name) -Destination $stage
}

# Windows 빌드와 동일한 예외: 로컬 LANS 사본의 손상된 로그 함수 두 개만 임시 복사본에서 제외한다.
# 계산/부호화/복호화 알고리즘과 원본 파일은 수정하지 않는다.
$source = Join-Path $stage 'afs_nav.c'
$contents = [IO.File]::ReadAllText($source)
$contents = [regex]::Replace($contents, 'static void bits_to_hex\(.*?\r?\n\}', 'static void bits_to_hex(const uint8_t* bits, int len, char* hex) { (void)bits; (void)len; if (hex) hex[0] = 0; }', [Text.RegularExpressions.RegexOptions]::Singleline)
$contents = [regex]::Replace($contents, 'void log_AFS_bits\(.*?\r?\n\}', 'void log_AFS_bits(FILE* fp, const char* id, int prn, int toi, int sb, const char* stage, const uint8_t* bits, int len) { (void)fp; (void)id; (void)prn; (void)toi; (void)sb; (void)stage; (void)bits; (void)len; }', [Text.RegularExpressions.RegexOptions]::Singleline)
[IO.File]::WriteAllText($source, $contents, [Text.UTF8Encoding]::new($false))

# WSL이 네트워크 드라이브를 직접 읽지 않아도 되도록 C 드라이브 임시 경로를 사용한다.
if (!$stage.StartsWith('C:\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'This WSL build helper requires TEMP on C:.'
}
$linuxStage = '/mnt/c' + $stage.Substring(2).Replace('\', '/')
Push-Location $env:SystemRoot
try {
    wsl.exe -d $Distribution --cd $linuxStage -- bash ./build-linux.sh
    if ($LASTEXITCODE -ne 0) { throw "Linux native build failed. Staging: $stage" }
} finally { Pop-Location }

$output = Join-Path $projectRoot 'build/native-linux'
New-Item -ItemType Directory -Path $output -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $stage 'libLnisAfsCodec.so') -Destination $output
Write-Output ('Linux candidate library: ' + $output)
Write-Output ('Build staging: ' + $stage)
