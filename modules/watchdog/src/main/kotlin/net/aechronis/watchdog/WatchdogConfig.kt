package net.aechronis.watchdog

import net.aechronis.watchdog.objects.Flag
import net.aechronis.watchdog.objects.FlagType
import net.minestom.server.entity.Player

data class WatchdogConfig(
    val enabledChecks: Set<FlagType> = FlagType.entries.toSet(),
    val flagThreshold: Double = 0.9,
    val evidenceWindowMillis: Long = 1_000,
    val maxHorizontalMovePerTick: Double = 0.9,
    val maxUpwardMovePerTick: Double = 0.7,
    val maxMovementPacketsPerSecond: Int = 40,
    val maxReach: Double = 3.2,
    val maxAttackAngleDegrees: Double = 90.0,
    val maxCps: Double = 20.0,
    val logPrefix: String = "Watchdog",
    val pingIntervalTicks: Int = 40,
    val pingTimeoutTicks: Int = 200,
    val translationProbeKeys: List<String> = emptyList(),
    val forbiddenTranslationKeys: Set<String> = translationProbeKeys.toSet(),
    val translationProbeTimeoutTicks: Int = 3,
    val openTranslationProbeEditor: Boolean = false,
    val staffAlertPermission: String = "watchdog.alert",
    val clientLabel: (Player) -> String = { it.settings.locale().toLanguageTag() },
    val bypass: (Player) -> Boolean = { false },
    val onFlag: (Flag) -> Unit = { flag ->
        System.err.println("Watchdog flag: ${flag.playerId} ${flag.type} ${flag.details}")
    },
) {
    init {
        require(flagThreshold in 0.0..1.0)
        require(evidenceWindowMillis > 0)
        require(maxHorizontalMovePerTick > 0.0)
        require(maxUpwardMovePerTick > 0.0)
        require(maxMovementPacketsPerSecond > 0)
        require(pingIntervalTicks > 0)
        require(pingTimeoutTicks >= pingIntervalTicks)
        require(translationProbeTimeoutTicks > 0)
        require(translationProbeKeys.all(String::isNotBlank))
        require(forbiddenTranslationKeys.all { it in translationProbeKeys })
        require(staffAlertPermission.isNotBlank())
    }
}
