package com.kai.mobilerat

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Overlay layar penuh. Dua mode:
 *
 *  - `black`  — layar hitam, sentuhan juga diserap
 *  - `block`  — transparan, semua sentuhan diserap (input terkunci)
 *
 * Butuh izin "tampil di atas aplikasi lain" (SYSTEM_ALERT_WINDOW). Izin itu
 * tidak bisa diminta lewat dialog runtime; pemilik perangkat harus
 * mengaktifkannya di Settings, kecuali aplikasi punya root.
 *
 * View-nya sengaja TANPA FLAG_NOT_TOUCHABLE: kalau flag itu dipasang, sentuhan
 * lolos ke aplikasi di bawahnya dan blokirnya tidak ada artinya.
 */
object Overlay {

    private var view: View? = null
    private var manager: WindowManager? = null

    fun canDraw(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    @Synchronized
    fun show(ctx: Context, black: Boolean): Boolean {
        if (!canDraw(ctx)) return false
        hide(ctx)
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val root = FrameLayout(ctx)
        root.setBackgroundColor(if (black) Color.BLACK else 0x01000000)
        root.isClickable = true
        root.isFocusable = true

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type, flags, PixelFormat.OPAQUE
        )
        lp.gravity = Gravity.TOP or Gravity.START
        return try {
            wm.addView(root, lp)
            view = root
            manager = wm
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun hide(ctx: Context?) {
        val v = view ?: return
        val m = manager ?: return
        try {
            m.removeView(v)
        } catch (_: Exception) {
        }
        view = null
        manager = null
    }

    fun active(): Boolean = view != null
}
