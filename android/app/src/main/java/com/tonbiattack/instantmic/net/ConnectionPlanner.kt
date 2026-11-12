package com.tonbiattack.instantmic.net

/**
 * Turns a discovery result into the connection parameters to use.
 *
 * The policy is pure so it can be unit tested, and so the rule "never silently
 * connect to a different PC or a changed certificate" is stated once.
 */
object ConnectionPlanner {

    /** What the app should do after a discovery update. */
    sealed interface Plan {
        /** Keep the stored address; nothing to change. */
        data object KeepStored : Plan

        /** Retry using a newly discovered address for the same PC. */
        data class SwitchAddress(val host: String, val port: Int) : Plan

        /** Stop and ask the user to pair again. */
        data class RequirePairing(val message: String) : Plan
    }

    /**
     * Decides how to react to [discovered] for a stored pairing.
     *
     * @param storedServerId server ID recorded at pairing time
     * @param storedFingerprint certificate fingerprint recorded at pairing time
     * @param storedHost address currently in use
     * @param storedPort port currently in use
     */
    fun plan(
        storedServerId: String,
        storedFingerprint: String,
        storedHost: String,
        storedPort: Int,
        discovered: DiscoveredReceiver?,
    ): Plan {
        if (discovered == null) return Plan.KeepStored

        return when (val verdict = ReconnectPolicy.evaluate(storedServerId, storedFingerprint, discovered)) {
            is ReconnectPolicy.Verdict.Reuse -> {
                val sameAddress = verdict.host == storedHost && verdict.port == storedPort
                if (sameAddress) Plan.KeepStored
                else Plan.SwitchAddress(verdict.host, verdict.port)
            }

            is ReconnectPolicy.Verdict.Repaired -> Plan.RequirePairing(verdict.message)

            ReconnectPolicy.Verdict.UseStoredAddress -> Plan.KeepStored
        }
    }

    /**
     * Picks the receiver to suggest in a manual "choose a PC" list.
     *
     * The stored server always wins; otherwise the list is de-duplicated and
     * sorted so the user sees a stable order. An unpaired PC is only ever
     * offered, never chosen automatically.
     */
    fun candidatesFor(
        receivers: List<DiscoveredReceiver>,
        storedServerId: String?,
    ): List<DiscoveredReceiver> {
        val unique = receivers.distinctBy { it.serverId }
        if (storedServerId != null) {
            val stored = unique.filter { it.matches(storedServerId) }
            val others = unique.filterNot { it.matches(storedServerId) }.sortedBy { it.serverName }
            return stored + others
        }
        return unique.sortedBy { it.serverName }
    }
}
