package net.aechronis.watchdog.checks

import net.aechronis.watchdog.objects.FlagType
import net.aechronis.watchdog.objects.KnockbackFrame
import net.aechronis.watchdog.objects.PlayerState
import net.aechronis.watchdog.objects.PlayerStateReg
import net.minestom.server.entity.Player
import kotlin.math.hypot

internal object VelocityCheck {
    fun check(
        player: Player,
        state: PlayerState,
        flag: FlagSink,
    ) {
        val current = player.position
        val expired = mutableListOf<KnockbackFrame>()
        for (expected in state.expectedKnockbacks) {
            val age = PlayerStateReg.currentTick - expected.createdTick
            if (age < 3) continue
            if (age > 8) {
                expired += expected
                continue
            }
            val horizontal = hypot(current.x - expected.position.x, current.z - expected.position.z)
            val expectedHorizontal = hypot(expected.expectedVelocity.x, expected.expectedVelocity.z)
            if (expectedHorizontal > 0.05 && horizontal < expectedHorizontal * 0.2) {
                flag(player, FlagType.VELOCITY, 0.35, "accepted ${expected.source} velocity with only $horizontal blocks movement")
            }
            expired += expected
        }
        state.expectedKnockbacks.removeAll(expired.toSet())
    }
}
