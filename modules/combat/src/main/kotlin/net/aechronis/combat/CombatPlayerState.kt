package net.aechronis.combat

import net.aechronis.combat.objects.Grenade
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.timer.Task

/** State whose lifetime follows a player, independent of item catalogue replacements. */
internal class CombatPlayerState {
    var aiming = false
    var aimingResetTask: Task? = null
    var reloadTask: Task? = null
    var placeTask: Task? = null
    var armedGrenade: Grenade? = null
    var grenadeFuseTask: Task? = null
    var grenadeFuseDeadline: Long? = null

    val previousPositions = ArrayDeque<Pos>()
    var speed = 0f
    var lastActionTime = 0L
    var meleeLastAttackTime = 0L
    var respawnProtectionExpiresAt: Long? = null

    fun cancelAimingReset() {
        val task = aimingResetTask
        aimingResetTask = null
        task?.cancel()
    }

    fun cancelReload() {
        val task = reloadTask
        reloadTask = null
        task?.cancel()
    }

    fun cancelPlacement() {
        val task = placeTask
        placeTask = null
        task?.cancel()
    }

    fun clearGrenade() {
        val task = grenadeFuseTask
        grenadeFuseTask = null
        task?.cancel()
        grenadeFuseDeadline = null
        armedGrenade = null
    }

    /** Catalogue changes cancel item actions without resetting movement, cooldowns or protection. */
    fun cancelActions() {
        cancelAimingReset()
        cancelReload()
        cancelPlacement()
        clearGrenade()
    }
}

internal class CombatPlayerStates {
    private val states = HashMap<Player, CombatPlayerState>()

    operator fun get(player: Player): CombatPlayerState? = states[player]

    fun getOrCreate(player: Player): CombatPlayerState = states.getOrPut(player, ::CombatPlayerState)

    fun remove(player: Player) {
        states.remove(player)?.cancelActions()
    }

    fun cancelActions() {
        states.values.forEach(CombatPlayerState::cancelActions)
    }

    fun clearAiming() {
        states.values.forEach { it.aiming = false }
    }

    fun clear() {
        try {
            cancelActions()
        } finally {
            states.clear()
        }
    }
}
