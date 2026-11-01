# tools\build.ps1 - build APK release dari command line.
#
# Kenapa perlu skrip: JAVA_HOME harus JDK 17+ (di sini G:\Java2, Java 25) dan
# Gradle harus dipanggil langsung karena folder project ini tidak punya wrapper.
# Tanpa JAVA_HOME yang benar, gradle.bat mati dengan "JAVA_HOME is not set".
#
# Pakai:
#   .\tools\build.ps1              -> assembleRelease
#   .\tools\build.ps1 -Clean       -> clean lalu build
#   .\tools\build.ps1 -Install     -> build lalu adb install -r

param(
    [switch]$Clean,
    [switch]$Install,
    [string]$JavaHome = "G:\Java2",
    [string]$Gradle = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.6.0-bin\42k10rwplmzkhuboz9kdazi7s\gradle-9.6.0\bin\gradle.bat"
)

$ErrorActionPreference = "Continue"
$root = Split-Path $PSScriptRoot -Parent

if (-not (Test-Path $JavaHome)) { throw "JAVA_HOME tidak ada: $JavaHome" }
if (-not (Test-Path $Gradle)) {
    $alt = "G:\Software\Gradle\gradle-8.9\bin\gradle.bat"
    if (Test-Path $alt) { $Gradle = $alt } else { throw "gradle tidak ketemu: $Gradle" }
}

$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"

# local.properties di-ignore git, jadi clone baru tidak punya SDK path dan
# Gradle mati dengan "SDK location not found". Dibuat otomatis kalau hilang.
$localProps = Join-Path $root "local.properties"
if (-not (Test-Path $localProps)) {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) {
        foreach ($cand in @("$env:LOCALAPPDATA\Android\Sdk", "G:\Software\AndroidSDK")) {
            if (Test-Path (Join-Path $cand "platform-tools")) { $sdk = $cand; break }
        }
    }
    if (-not $sdk) { throw "SDK Android tidak ketemu; set ANDROID_HOME atau isi local.properties" }
    # Titik dua di path Windows harus di-escape di file properties.
    "sdk.dir=" + ($sdk -replace '\\', '\\' -replace '^([A-Za-z]):', '$1\:') |
        Set-Content -Path $localProps -Encoding ASCII
    Write-Host "[*] local.properties dibuat: sdk.dir=$sdk"
}

# Keystore wajib. Tanpa ini APK keluar unsigned dan Android menolaknya.
if (-not (Test-Path (Join-Path $root "keystore.properties"))) {
    Write-Host "[!] keystore.properties belum ada, menjalankan tools\genkey.ps1"
    & (Join-Path $PSScriptRoot "genkey.ps1")
}

Push-Location $root
try {
    if ($Clean) {
        Write-Host "[*] clean"
        & $Gradle --no-daemon --console=plain clean
    }
    Write-Host "[*] assembleRelease"
    & $Gradle --no-daemon --console=plain assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "build gagal (exit $LASTEXITCODE)" }

    $apk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $apk)) { throw "APK tidak terbentuk: $apk" }

    $dist = Join-Path $root "dist"
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $out = Join-Path $dist "SystemService.apk"
    Copy-Item $apk $out -Force

    $size = [math]::Round((Get-Item $out).Length / 1KB, 1)
    $sha = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()
    Write-Host "[+] apk    : $out ($size KB)"
    Write-Host "[+] sha256 : $sha"

    $bt = Get-ChildItem "G:\Software\AndroidSDK\build-tools" -Directory -EA SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($bt) {
        Write-Host "[*] verifikasi tanda tangan"
        & (Join-Path $bt.FullName "apksigner.bat") verify --print-certs $out 2>&1 |
            Select-String -Pattern 'DN:|SHA-256 digest' | ForEach-Object { "    " + $_.Line.Trim() }
    }

    if ($Install) {
        $adb = "G:\Software\AndroidSDK\platform-tools\adb.exe"
        if (Test-Path $adb) {
            & $adb install -r $out
        } else {
            Write-Host "[!] adb tidak ketemu"
        }
    }
} finally {
    Pop-Location
}
