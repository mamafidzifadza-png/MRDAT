package com.kai.mobilerat

import android.os.Environment
import java.io.File

/**
 * Resolusi path. Aplikasi berjalan sebagai uid sendiri, jadi `/sdcard` hanya
 * bisa dibaca-tulis kalau `requestLegacyExternalStorage` masih berlaku —
 * itulah alasan targetSdk dipatok 29. Kalau tetap ditolak (storage manager
 * dimatikan pengguna), semua perintah jatuh ke folder privat aplikasi dan
 * melapor apa adanya, bukan diam-diam kosong.
 */
object Vfs {

    val external: File
        get() = Environment.getExternalStorageDirectory()

    val private: File
        get() = File("/data/data/com.kai.mobilerat")

    fun resolve(cwd: String, arg: String): File {
        val a = arg.trim().trim('"')
        return when {
            a.isEmpty() -> File(cwd)
            a == "~" -> external
            a.startsWith("/") -> File(a)
            else -> File(cwd, a)
        }
    }

    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.size - 1) {
            v /= 1024.0
            i++
        }
        return String.format("%.1f %s", v, units[i])
    }

    fun writable(dir: File): Boolean = try {
        dir.isDirectory && dir.canWrite()
    } catch (_: Exception) {
        false
    }
}
