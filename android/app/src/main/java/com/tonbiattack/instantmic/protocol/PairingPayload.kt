package com.tonbiattack.instantmic.protocol

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The pairing QR payload shared with the Windows receiver.
 *
 * The payload contains no long-term token: only a one-time secret that the
 * receiver consumes on the first successful pairing, plus the address and
 * certificate fingerprint so the phone can pin TLS immediately. A scanned code
 * is never trusted on its own — the receiver still verifies the secret.
 */
data class PairingPayload(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val secret: String,
    val serverId: String,
    val serverName: String,
    val expiresAtMillis: Long,
) {
    /** True when the code is older than its expiry, allowing clock skew. */
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis > expiresAtMillis + EXPIRY_SKEW_MS

    companion object {
        const val VERSION = 1
        const val SCHEME = "instantmic"
        private const val PREFIX = "$SCHEME://pair?"
        private const val EXPIRY_SKEW_MS = 30_000L

        /** Parses a scanned string, returning null when it is not a valid payload. */
        fun parse(raw: String?): PairingPayload? {
            val text = raw?.trim().orEmpty()
            if (!text.startsWith(PREFIX)) return null
            val params = try {
                parseQuery(text.removePrefix(PREFIX))
            } catch (e: Exception) {
                return null
            }

            if (params["v"] != VERSION.toString()) return null

            val host = params["h"].orEmpty()
            if (host.isEmpty()) return null

            val port = params["p"]?.toIntOrNull() ?: return null
            if (port !in 1..65535) return null

            val fingerprint = params["f"].orEmpty().lowercase()
            if (!fingerprint.matches(Regex("[0-9a-f]{64}"))) return null

            val secret = params["t"].orEmpty()
            if (secret.length < 16) return null

            val expires = params["e"]?.toLongOrNull() ?: return null
            if (expires <= 0) return null

            return PairingPayload(
                host = host,
                port = port,
                fingerprint = fingerprint,
                secret = secret,
                serverId = params["s"].orEmpty(),
                serverName = params["n"].orEmpty().ifEmpty { host },
                expiresAtMillis = expires,
            )
        }

        private fun parseQuery(query: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            for (pair in query.split("&")) {
                if (pair.isEmpty()) continue
                val idx = pair.indexOf('=')
                if (idx <= 0) return emptyMap()
                val key = decode(pair.substring(0, idx))
                val value = decode(pair.substring(idx + 1))
                out[key] = value
            }
            return out
        }

        private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

        // Encoding lives in the tests and the receiver; the app only parses.
        internal fun encodeQuery(values: Map<String, String>): String =
            values.entries.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }
    }
}
