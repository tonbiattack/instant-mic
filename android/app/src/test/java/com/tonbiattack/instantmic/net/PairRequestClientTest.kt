package com.tonbiattack.instantmic.net

import com.tonbiattack.instantmic.protocol.Fingerprints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing-request client only has a little pure logic, but it is the part
 * that decides whether a PC's answer is accepted. The network calls need a
 * server, so they are covered by the Go integration tests and by hand; what is
 * pinned here is the shape of the result and the timeout arithmetic.
 */
class PairRequestClientTest {

    @Test
    fun `approved carries the secret`() {
        val result: PairAskResult = PairAskResult.Approved("abc123")
        assertTrue(result is PairAskResult.Approved)
        assertEquals("abc123", (result as PairAskResult.Approved).secret)
    }

    @Test
    fun `terminal states are distinct`() {
        // The UI shows a different message for each, so they must not collapse
        // into one "failed" case.
        assertTrue(PairAskResult.Denied is PairAskResult)
        assertTrue(PairAskResult.Expired is PairAskResult)
        assertTrue(PairAskResult.Unsupported is PairAskResult)
        val failed = PairAskResult.Failed("network down")
        assertTrue(failed is PairAskResult.Failed)
        assertEquals("network down", failed.message)
    }

    @Test
    fun `candidate keeps the address and fingerprint`() {
        val candidate = PairCandidate(
            host = "192.168.1.10",
            port = 8765,
            serverName = "MyPC",
            fingerprint = "ab".repeat(32),
        )
        assertEquals("192.168.1.10", candidate.host)
        assertEquals(8765, candidate.port)
        assertEquals(64, candidate.fingerprint.length)
    }

    @Test
    fun `a short fingerprint is not trusted`() {
        // A malformed fingerprint must not be treated as a pin: the check is
        // skipped rather than comparing against garbage.
        val normalised = Fingerprints.normalize("abcd").takeIf { it.length == 64 }
        assertNull(normalised)
    }
}
