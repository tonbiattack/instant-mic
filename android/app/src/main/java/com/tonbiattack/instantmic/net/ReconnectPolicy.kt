package com.tonbiattack.instantmic.net

import java.security.MessageDigest

/**
 * Decides whether a stored pairing may be reused automatically.
 *
 * Auto-reconnect must never silently connect to a different PC or a PC whose
 * certificate changed after pairing: that is exactly the case where an attacker
 * on the LAN could take over the connection. Anything suspicious falls back to
 * "ask the user to pair again" rather than retrying quietly.
 */
object ReconnectPolicy {

    /** Why automatic reconnection cannot proceed. */
    sealed interface Verdict {
        /** The stored pairing may be reused; [pairing] has the current address. */
        data class Reuse(
            val host: String,
            val port: Int,
            val fingerprint: String,
        ) : Verdict

        /** The pairing must be redone. [message] explains why in Japanese. */
        data class Repaired(val message: String) : Verdict

        /** Keep using the stored address; discovery is optional. */
        data object UseStoredAddress : Verdict
    }

    /**
     * Applies a discovered receiver to a stored pairing.
     *
     * A different server ID is simply a different PC on the same LAN: it must
     * never be used for the stored pairing, but it must also not block the
     * stored address from working. A changed fingerprint for the *same* PC is
     * different — that is the case an attacker on the LAN would produce, so the
     * user has to pair again.
     */
    fun evaluate(
        storedServerId: String,
        storedFingerprint: String,
        discovered: DiscoveredReceiver?,
    ): Verdict {
        if (discovered == null) return Verdict.UseStoredAddress

        if (storedServerId.isNotEmpty() && discovered.serverId != storedServerId) {
            // Someone else's PC: ignore it for this pairing.
            return Verdict.UseStoredAddress
        }
        if (storedFingerprint.isNotEmpty() && discovered.fingerprint.isNotEmpty() &&
            !sameFingerprint(storedFingerprint, discovered.fingerprint)
        ) {
            return Verdict.Repaired(
                "PCの証明書が変わりました。安全のため自動接続を中止しました。PC側でペアリングし直してください。",
            )
        }
        return Verdict.Reuse(
            host = discovered.host,
            port = discovered.port,
            fingerprint = discovered.fingerprint.ifEmpty { storedFingerprint },
        )
    }

    /** Compares fingerprints ignoring separators and case. */
    fun sameFingerprint(a: String, b: String): Boolean =
        normalize(a) == normalize(b) && normalize(a).isNotEmpty()

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isDigit() || it in 'a'..'f' }

    /** Short, human readable fingerprint for the UI. */
    fun shortFingerprint(value: String): String {
        val hex = normalize(value)
        if (hex.length < 16) return hex
        return hex.chunked(4).take(4).joinToString(":")
    }

    /** Constant-time comparison helper for fingerprints. */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(normalize(a).toByteArray(), normalize(b).toByteArray())
}
