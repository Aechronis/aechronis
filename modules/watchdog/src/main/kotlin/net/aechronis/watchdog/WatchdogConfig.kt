package net.aechronis.watchdog

import net.aechronis.watchdog.objects.Flag
import net.aechronis.watchdog.objects.FlagType
import net.minestom.server.entity.Player

data class WatchdogConfig(
    val enabledChecks: Set<FlagType> =
        setOf(
            FlagType.BAD_PACKETS,
            FlagType.FLIGHT,
            FlagType.SPEED,
            FlagType.TIMER,
        ),
    val flagThreshold: Double = 0.95,
    val evidenceWindowMillis: Long = 1_500,
    val maxHorizontalMovePerTick: Double = 0.9,
    val maxUpwardMovePerTick: Double = 0.7,
    val maxMovementPacketsPerSecond: Int = 45,
    val maxReach: Double = 3.2,
    val maxAttackAngleDegrees: Double = 120.0,
    val maxCps: Double = 22.0,
    val alertCooldownMillis: Long = 5_000,
    val logPrefix: String = "Watchdog",
    val pingIntervalTicks: Int = 40,
    val pingTimeoutTicks: Int = 200,
    val translationProbeKeys: List<String> = emptyList(),
    val forbiddenTranslationKeys: Set<String> = translationProbeKeys.toSet(),
    val translationProbeTimeoutTicks: Int = 3,
    val openTranslationProbeEditor: Boolean = false,
    val staffAlertPermission: String = "watchdog.alarts",
    val clientLabel: (Player) -> String = { it.settings.locale().toLanguageTag() },
    val bypass: (Player) -> Boolean = { false },
    val onFlag: (Flag) -> Unit = {},
) {
    init {
        require(flagThreshold in 0.0..1.0)
        require(evidenceWindowMillis > 0)
        require(maxHorizontalMovePerTick > 0.0)
        require(maxUpwardMovePerTick > 0.0)
        require(maxMovementPacketsPerSecond > 0)
        require(maxReach > 0.0)
        require(maxAttackAngleDegrees in 0.0..180.0)
        require(maxCps > 0.0)
        require(alertCooldownMillis >= 0)
        require(pingIntervalTicks > 0)
        require(pingTimeoutTicks >= pingIntervalTicks)
        require(translationProbeTimeoutTicks > 0)
        require(translationProbeKeys.all(String::isNotBlank))
        require(forbiddenTranslationKeys.all { it in translationProbeKeys })
        require(staffAlertPermission.isNotBlank())
    }
}
