package com.tonbiattack.instantmic.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadTest {

    private val fingerprint = "ab".repeat(32)
    private val secret = "0123456789abcdefghijklmn"
    private val expires = 1_700_000_000_000L

    private fun build(
        host: String = "192.168.1.20",
        port: Int = 8765,
        fp: String = fingerprint,
        secretValue: String = secret,
        version: Int = 1,
        expiresAt: Long = expires,
    ): String {
        val values = linkedMapOf(
            "v" to version.toString(),
            "h" to host,
            "p" to port.toString(),
            "f" to fp,
            "t" to secretValue,
            "s" to "srv-1",
            "n" to "デスクPC",
            "e" to expiresAt.toString(),
        )
        return PairingPayload.SCHEME + "://pair?" + PairingPayload.encodeQuery(values)
    }

    @Test
    fun `round trip from the receiver format`() {
        val payload = PairingPayload.parse(build())
        assertNotNull(payload)
        assertEquals("192.168.1.20", payload!!.host)
        assertEquals(8765, payload.port)
        assertEquals(fingerprint, payload.fingerprint)
        assertEquals(secret, payload.secret)
        assertEquals("srv-1", payload.serverId)
        assertEquals("デスクPC", payload.serverName)
        assertEquals(expires, payload.expiresAtMillis)
    }

    @Test
    fun `missing server name falls back to the host`() {
        val raw = PairingPayload.SCHEME + "://pair?" + PairingPayload.encodeQuery(
            linkedMapOf(
                "v" to "1",
                "h" to "10.0.0.5",
                "p" to "8765",
                "f" to fingerprint,
                "t" to secret,
                "e" to expires.toString(),
            ),
        )
        val payload = PairingPayload.parse(raw)
        assertEquals("10.0.0.5", payload!!.serverName)
    }

    @Test
    fun `invalid payloads are rejected`() {
        val cases = listOf(
            null,
            "",
            "https://pair",
            PairingPayload.SCHEME + "://pair?",
            build(version = 2),
            build(host = ""),
            build(port = 0),
            build(port = 70000),
            build(fp = "zz".repeat(32)),
            build(fp = "abc"),
            build(secretValue = "short"),
            build(expiresAt = 0),
            "instantmic://pair?not-a-query",
        )
        for (raw in cases) {
            assertNull("$raw", PairingPayload.parse(raw))
        }
    }

    @Test
    fun `expiry allows a little clock skew`() {
        val payload = PairingPayload.parse(build())!!
        assertFalse(payload.isExpired(expires - 1_000))
        assertFalse("small skew should still be usable", payload.isExpired(expires + 10_000))
        assertTrue(payload.isExpired(expires + 60_000))
    }
}
