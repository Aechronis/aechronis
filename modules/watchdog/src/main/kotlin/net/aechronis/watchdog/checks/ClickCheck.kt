package net.aechronis.watchdog.checks

import net.aechronis.watchdog.WatchdogConfig
import net.aechronis.watchdog.objects.FlagType
import net.aechronis.watchdog.objects.PlayerState
import net.minestom.server.entity.Player
import kotlin.math.sqrt

internal object ClickCheck {
    fun check(
        player: Player,
        state: PlayerState,
        config: WatchdogConfig,
        flag: FlagSink,
    ) {
        val now = System.currentTimeMillis()
        val recent = state.swingsAtMillis.filter { now - it <= 1_000L }
        if (recent.size < 15 || recent.size == state.lastEvaluatedSwingCount) return
        state.lastEvaluatedSwingCount = recent.size
        val cps = recent.size.toDouble()
        val intervals = recent.zipWithNext { left, right -> (right - left).toDouble() }
        val average = intervals.average()
        val deviation =
            sqrt(intervals.map { value -> (value - average) * (value - average) }.average())
        if (cps > config.maxCps) {
            flag(player, FlagType.AUTO_CLICKER, 0.45, "attack animation rate was $cps CPS")
        }
        if (intervals.size >= 14 && deviation < 2.5) {
            flag(player, FlagType.AUTO_CLICKER, 0.3, "attack interval deviation was ${deviation}ms")
        }
    }
}
