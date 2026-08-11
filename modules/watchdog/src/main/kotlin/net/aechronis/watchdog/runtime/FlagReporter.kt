package net.aechronis.watchdog.runtime

import net.aechronis.watchdog.WatchdogConfig
import net.aechronis.watchdog.alert.StaffAlert
import net.aechronis.watchdog.objects.FlagType
import net.aechronis.watchdog.objects.PlayerState
import net.minestom.server.entity.Player

internal class FlagReporter(
    private val config: WatchdogConfig,
    private val state: (Player) -> PlayerState,
    private val alerts: StaffAlert,
) {
    private val lastAlerts = mutableMapOf<Pair<java.util.UUID, FlagType>, Long>()

    fun report(
        player: Player,
        type: FlagType,
        certainty: Double,
        details: String,
    ) {
        if (type !in config.enabledChecks) return
        val result = state(player).evidence.add(type, certainty, details, config)
        if (result != null) {
            config.onFlag(result)
            val key = player.uuid to type
            val now = result.timestampMillis
            val previous = lastAlerts[key]
            if (previous == null || now - previous >= config.alertCooldownMillis) {
                lastAlerts[key] = now
                alerts.notify(result)
            }
        }
    }
}
