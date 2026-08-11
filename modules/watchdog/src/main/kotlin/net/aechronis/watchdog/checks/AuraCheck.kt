package net.aechronis.watchdog.checks

import net.aechronis.watchdog.objects.FlagType
import net.aechronis.watchdog.objects.PlayerState
import net.minestom.server.entity.Player

internal object AuraCheck {
    fun check(
        player: Player,
        state: PlayerState,
        flag: FlagSink,
    ) {
        if (state.attacks.size == state.lastEvaluatedAttackCount) return
        state.lastEvaluatedAttackCount = state.attacks.size
        val now = System.currentTimeMillis()
        val recent = state.attacks.filter { it.confirmed && now - it.timestampMillis <= 1_000L }
        if (recent.size < 8) return
        val targets = recent.map { it.targetId }.distinct().size
        if (targets >= 4) {
            flag(player, FlagType.KILL_AURA, 0.3, "attacked $targets targets ${recent.size} times in one second")
        }
    }
}
