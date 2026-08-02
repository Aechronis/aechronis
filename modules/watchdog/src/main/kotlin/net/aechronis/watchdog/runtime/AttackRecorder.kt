package net.aechronis.watchdog.runtime

import net.aechronis.watchdog.WatchdogConfig
import net.aechronis.watchdog.checks.CombatChecks
import net.aechronis.watchdog.checks.FlagSink
import net.aechronis.watchdog.objects.AttackFrame
import net.aechronis.watchdog.objects.KnockbackFrame
import net.aechronis.watchdog.objects.PlayerState
import net.aechronis.watchdog.objects.PlayerStateReg
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Player

internal class AttackRecorder(
    private val config: WatchdogConfig,
    private val state: (Player) -> PlayerState,
    private val flag: FlagSink,
) {
    fun reportAttack(
        attacker: Player,
        target: Player,
    ) = recordAttack(attacker, target, confirmed = true)

    fun recordAttackAttempt(
        attacker: Player,
        target: Player,
    ) = recordAttack(attacker, target, confirmed = false)

    fun recordSwing(player: Player) {
        state(player).recordSwing(System.currentTimeMillis())
    }

    fun recordKnockback(
        player: Player,
        expectedVelocity: Vec,
        source: String,
    ) {
        val playerState = state(player)
        playerState.expectedKnockbacks.addLast(
            KnockbackFrame(PlayerStateReg.currentTick, player.position, expectedVelocity, source),
        )
        while (playerState.expectedKnockbacks.size > 8) playerState.expectedKnockbacks.removeFirst()
    }

    private fun recordAttack(
        attacker: Player,
        target: Player,
        confirmed: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val playerState = state(attacker)
        playerState.lastAttackTarget = target.uuid
        playerState.lastAttackAtMillis = now
        val attack =
            playerState.recordAttack(
                AttackFrame(
                    timestampMillis = now,
                    targetId = target.uuid,
                    attackerPosition = attacker.position,
                    targetPosition = target.position,
                    targetWidth = target.boundingBox.width(),
                    targetHeight = target.boundingBox.height(),
                    yaw = attacker.position.yaw,
                    pitch = attacker.position.pitch,
                    confirmed = confirmed,
                ),
            )
        CombatChecks.onAttack(attacker, attack, playerState, config, flag)
    }
}
