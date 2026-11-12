package com.tonbiattack.instantmic.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/** Generates unique operation IDs so retries can be de-duplicated server-side. */
object OperationIds {
    private val random = SecureRandom()
    private val counter = AtomicLong()

    fun next(): String {
        val bytes = ByteArray(9)
        random.nextBytes(bytes)
        val sb = StringBuilder(24)
        for (b in bytes) sb.append("%02x".format(b))
        sb.append('-')
        sb.append(counter.incrementAndGet().toString(36))
        return sb.toString()
    }
}

/** Utility for generating pairing device IDs. */
object DeviceIds {
    private val random = SecureRandom()

    fun newId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/** Certificate fingerprint helpers for TLS pinning. */
object Fingerprints {
    fun sha256Hex(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    /** Accepts `aa:bb:…`, `AABB…` and spaced input, returning lowercase hex. */
    fun normalize(value: String): String =
        value.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
}
