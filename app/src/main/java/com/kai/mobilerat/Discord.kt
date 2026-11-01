package com.kai.mobilerat

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lapisan HTTP Discord. Tiga hal yang wajib ada di sini dan tidak ada di
 * versi lama:
 *
 *  1. 429 dihormati. `retry_after` dibaca dari body, bukan ditebak.
 *  2. Upload pakai multipart stream, bukan base64 di dalam pesan. File 2 MB
 *     kalau di-base64 jadi ~2.700 pesan dan bot langsung kena rate limit.
 *  3. Connection selalu di-disconnect, termasuk saat exception.
 */
object Discord {

    private fun open(path: String): HttpURLConnection {
        val c = URL(Config.api + path).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bot " + Config.token)
        c.setRequestProperty("User-Agent", "DiscordBot (https://discord.com/api, 10)")
        c.connectTimeout = 15000
        c.readTimeout = 60000
        return c
    }

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun retryMs(body: String, fallback: Long): Long = try {
        val ms = (JSONObject(body).optDouble("retry_after", 1.0) * 1000.0).toLong()
        if (ms in 1..120_000) ms else fallback
    } catch (_: Exception) {
        fallback
    }

    fun call(method: String, path: String, json: String? = null): Pair<Int, String> {
        var attempt = 0
        while (true) {
            var c: HttpURLConnection? = null
            try {
                c = open(path)
                c.requestMethod = method
                if (json != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code == 429 && attempt < 4) {
                    attempt++
                    pause(retryMs(text, 1500L * attempt))
                    continue
                }
                return code to text
            } catch (e: Exception) {
                if (attempt >= 2) return -1 to (e.message ?: "io error")
                attempt++
                pause(1200L * attempt)
            } finally {
                try {
                    c?.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun send(text: String): Boolean {
        val body = JSONObject().put("content", text).toString()
        val r = call("POST", "/channels/${Config.channel}/messages", body)
        return r.first in 200..299
    }

    fun sendLong(text: String, prefix: String) {
        val limit = 1900 - prefix.length
        var i = 0
        while (i < text.length) {
            val end = minOf(i + limit, text.length)
            send(prefix + text.substring(i, end))
            i = end
            if (i < text.length) pause(380)
        }
        if (text.isEmpty()) send(prefix + "(kosong)")
    }

    /**
     * Upload attachment. Panjang body dihitung di depan supaya bisa dipakai
     * setFixedLengthStreamingMode — file besar tidak pernah masuk memori
     * sekaligus.
     */
    fun upload(file: File, caption: String, tries: Int = 2): Boolean {
        val boundary = "----mr" + System.nanoTime()
        val name = file.name.replace("\"", "").replace("\r", "").replace("\n", "")
        val head = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"content\"\r\n\r\n" +
                caption + "\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"files[0]\"; filename=\"$name\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        var attempt = 0
        while (attempt < tries) {
            attempt++
            var c: HttpURLConnection? = null
            try {
                c = open("/channels/${Config.channel}/messages")
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                val total = head.size.toLong() + file.length() + tail.size.toLong()
                c.setFixedLengthStreamingMode(total)
                c.outputStream.use { out ->
                    out.write(head)
                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    out.write(tail)
                    out.flush()
                }
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code == 429 && attempt < tries) {
                    pause(retryMs(body, 2000))
                    continue
                }
                return code in 200..299
            } catch (e: Exception) {
                if (attempt >= tries) return false
                pause(1500L * attempt)
            } finally {
                try {
                    c?.disconnect()
                } catch (_: Exception) {
                }
            }
        }
        return false
    }

    fun download(url: String, dest: File): Pair<Boolean, String> {
        var c: HttpURLConnection? = null
        return try {
            dest.parentFile?.mkdirs()
            c = URL(url).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            c.connectTimeout = 15000
            c.readTimeout = 90000
            c.inputStream.use { ins ->
                dest.outputStream().use { outs -> ins.copyTo(outs, 64 * 1024) }
            }
            true to "OK ${dest.absolutePath} (${dest.length()} B)"
        } catch (e: Exception) {
            false to "ERR ${e.message}"
        } finally {
            try {
                c?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun attachments(message: JSONObject): List<String> {
        val arr = message.optJSONArray("attachments") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val u = a.optString("url")
            if (u.isNotEmpty()) out.add(u)
        }
        return out
    }
}
