package com.tonbiattack.instantmic.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPlannerTest {

    private val fingerprint = "ab".repeat(32)

    private fun discovered(
        serverId: String = "srv-1",
        host: String = "192.168.1.50",
        port: Int = 8765,
        fp: String = fingerprint,
    ) = DiscoveredReceiver("$serverId", "デスクPC", host, port, fp, hasTls = true)

    @Test
    fun `same address keeps the stored connection`() {
        val plan = ConnectionPlanner.plan("srv-1", fingerprint, "192.168.1.50", 8765, discovered())
        assertEquals(ConnectionPlanner.Plan.KeepStored, plan)
    }

    @Test
    fun `a new address switches without re-pairing`() {
        val plan = ConnectionPlanner.plan("srv-1", fingerprint, "192.168.1.10", 8765, discovered())
        assertEquals(
            ConnectionPlanner.Plan.SwitchAddress("192.168.1.50", 8765),
            plan,
        )
    }

    @Test
    fun `a different server never changes the stored address`() {
        val plan = ConnectionPlanner.plan("srv-1", fingerprint, "192.168.1.10", 8765, discovered(serverId = "srv-2"))
        assertEquals(ConnectionPlanner.Plan.KeepStored, plan)
    }

    @Test
    fun `a changed certificate requires pairing`() {
        val plan = ConnectionPlanner.plan(
            "srv-1", fingerprint, "192.168.1.10", 8765,
            discovered(fp = "cd".repeat(32)),
        )
        assertTrue(plan is ConnectionPlanner.Plan.RequirePairing)
    }

    @Test
    fun `no discovery keeps the stored address`() {
        assertEquals(
            ConnectionPlanner.Plan.KeepStored,
            ConnectionPlanner.plan("srv-1", fingerprint, "192.168.1.10", 8765, null),
        )
    }

    @Test
    fun `candidate list puts the stored server first and hides duplicates`() {
        val stored = discovered(serverId = "srv-1", host = "192.168.1.50")
        val other = discovered(serverId = "srv-2", host = "192.168.1.60")
        val duplicate = stored.copy(host = "192.168.1.51")

        val ordered = ConnectionPlanner.candidatesFor(listOf(other, duplicate, stored), "srv-1")
        assertEquals(listOf("srv-1", "srv-2"), ordered.map { it.serverId })
    }

    @Test
    fun `candidates are sorted when nothing is stored`() {
        val b = DiscoveredReceiver("b", "B機", "10.0.0.2", 8765, fingerprint, true)
        val a = DiscoveredReceiver("a", "A機", "10.0.0.1", 8765, fingerprint, true)
        assertEquals(listOf("a", "b"), ConnectionPlanner.candidatesFor(listOf(b, a), null).map { it.serverId })
    }
}
