# tools\genkey.ps1 - buat keystore signing + keystore.properties.
#
# Tanpa ini, `assembleRelease` menghasilkan APK unsigned dan Android menolak
# memasangnya dengan INSTALL_PARSE_FAILED_NO_CERTIFICATES. Itu blocker nomor
# satu di versi lama.
#
# Pakai:
#   .\tools\genkey.ps1
#   .\tools\genkey.ps1 -Force        (timpa keystore lama; APK lama tidak bisa
#                                     di-update di atasnya, harus uninstall dulu)

param(
    [string]$Store = "mobilerat.jks",
    [string]$Alias = "mobilerat",
    [string]$Dname = "CN=System Service, OU=Update, O=System, L=Jakarta, C=ID",
    [int]$Validity = 10000,
    [switch]$Force
)

# keytool menulis progres ke stderr, dan PowerShell 5.1 menganggap itu error
# yang mematikan kalau ErrorActionPreference = "Stop". Keberhasilan dicek lewat
# ada tidaknya file, bukan lewat exit code.
$ErrorActionPreference = "Continue"
$root = Split-Path $PSScriptRoot -Parent
$jks = Join-Path $root $Store
$props = Join-Path $root "keystore.properties"

$keytool = 'G:\Java2\bin\keytool.exe'
if (-not (Test-Path $keytool)) {
    $cmd = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($cmd) { $keytool = $cmd.Source } else { throw "keytool tidak ketemu" }
}

if ((Test-Path $jks) -and -not $Force) {
    Write-Host "[*] keystore sudah ada: $jks (pakai -Force untuk menimpa)"
} else {
    if (Test-Path $jks) { Remove-Item $jks -Force }
    # Password acak tapi dicatat di keystore.properties, bukan di build.gradle.
    $bytes = New-Object byte[] 18
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $pass = [Convert]::ToBase64String($bytes).Replace("/", "s").Replace("+", "p").Replace("=", "")

    & $keytool -genkeypair -v `
        -keystore $jks `
        -storetype JKS `
        -alias $Alias `
        -keyalg RSA -keysize 2048 -validity $Validity `
        -storepass $pass -keypass $pass `
        -dname $Dname 2>&1 | ForEach-Object { "    $_" }

    if (-not (Test-Path $jks)) { throw "keytool gagal membuat keystore" }

    @(
        "storeFile=$Store"
        "storePassword=$pass"
        "keyAlias=$Alias"
        "keyPassword=$pass"
    ) | Set-Content -Path $props -Encoding ASCII
    Write-Host "[+] keystore : $jks"
    Write-Host "[+] props    : $props"
}

# Sidik jari sertifikat - ini yang menentukan APK bisa update di atas APK lama.
& $keytool -list -v -keystore $jks -storepass ((Get-Content $props | Where-Object { $_ -like 'storePassword=*' }) -replace '^storePassword=', '') -alias $Alias 2>&1 |
    Select-String -Pattern 'SHA1:|SHA-256:|Alias name|Valid from' | ForEach-Object { "    " + $_.Line.Trim() }
