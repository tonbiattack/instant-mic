package com.tonbiattack.instantmic.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    private val fingerprint = "ab".repeat(32)
    private val otherFingerprint = "cd".repeat(32)

    private fun discovered(
        serverId: String = "srv-1",
        host: String = "192.168.1.50",
        port: Int = 8765,
        fp: String = fingerprint,
    ) = DiscoveredReceiver(
        serverId = serverId,
        serverName = "デスクPC",
        host = host,
        port = port,
        fingerprint = fp,
        hasTls = true,
    )

    @Test
    fun `same server and certificate reuses the new address`() {
        val verdict = ReconnectPolicy.evaluate("srv-1", fingerprint, discovered())
        assertTrue(verdict is ReconnectPolicy.Verdict.Reuse)
        val reuse = verdict as ReconnectPolicy.Verdict.Reuse
        assertEquals("192.168.1.50", reuse.host)
        assertEquals(8765, reuse.port)
        assertEquals(fingerprint, reuse.fingerprint)
    }

    @Test
    fun `a different server is ignored instead of blocking the stored one`() {
        val verdict = ReconnectPolicy.evaluate("srv-1", fingerprint, discovered(serverId = "srv-2"))
        assertEquals(ReconnectPolicy.Verdict.UseStoredAddress, verdict)
    }

    @Test
    fun `a changed certificate requires re-pairing`() {
        val verdict = ReconnectPolicy.evaluate("srv-1", fingerprint, discovered(fp = otherFingerprint))
        assertTrue(verdict is ReconnectPolicy.Verdict.Repaired)
        assertTrue((verdict as ReconnectPolicy.Verdict.Repaired).message.contains("証明書"))
    }

    @Test
    fun `discovery is optional`() {
        assertEquals(
            ReconnectPolicy.Verdict.UseStoredAddress,
            ReconnectPolicy.evaluate("srv-1", fingerprint, null),
        )
    }

    @Test
    fun `an empty discovered fingerprint keeps the stored one`() {
        val verdict = ReconnectPolicy.evaluate("srv-1", fingerprint, discovered(fp = ""))
        assertTrue(verdict is ReconnectPolicy.Verdict.Reuse)
        assertEquals(fingerprint, (verdict as ReconnectPolicy.Verdict.Reuse).fingerprint)
    }

    @Test
    fun `fingerprints compare ignoring case and separators`() {
        val spaced = fingerprint.chunked(2).joinToString(":").uppercase()
        assertTrue(ReconnectPolicy.sameFingerprint(fingerprint, spaced))
        assertFalse(ReconnectPolicy.sameFingerprint(fingerprint, otherFingerprint))
        assertFalse(ReconnectPolicy.sameFingerprint("", ""))
    }

    @Test
    fun `short fingerprint groups hex pairs`() {
        assertEquals("abab:abab:abab:abab", ReconnectPolicy.shortFingerprint(fingerprint))
        assertEquals("abc", ReconnectPolicy.shortFingerprint("abc"))
    }

    @Test
    fun `constant time compare matches sameFingerprint`() {
        val spaced = fingerprint.chunked(4).joinToString("-")
        assertTrue(ReconnectPolicy.constantTimeEquals(fingerprint, spaced))
        assertFalse(ReconnectPolicy.constantTimeEquals(fingerprint, otherFingerprint))
    }
}
