package net.aechronis.watchdog.objects

import net.aechronis.watchdog.WatchdogConfig
import net.aechronis.watchdog.objects.Flag
import net.aechronis.watchdog.objects.FlagType
import java.util.UUID

internal class EvidenceTracker(
    private val playerId: UUID,
) {
    private val evidence = mutableMapOf<FlagType, Evidence>()

    fun add(
        type: FlagType,
        certainty: Double,
        details: String,
        config: WatchdogConfig,
        now: Long = System.currentTimeMillis(),
    ): Flag? {
        val value = certainty.coerceIn(0.0, 1.0)
        val current = evidence[type]
        val updated =
            if (current == null || now - current.timestampMillis > config.evidenceWindowMillis) {
                Evidence(value, details, now)
            } else {
                Evidence((current.certainty + value).coerceAtMost(1.0), details, now)
            }
        evidence[type] = updated
        if (updated.certainty < config.flagThreshold) return null

        evidence.remove(type)
        return Flag(playerId, type, updated.certainty, updated.details, now)
    }
}
