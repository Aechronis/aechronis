package net.aechronis.combat

import net.aechronis.combat.objects.Throwable
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.timer.Task

/** State whose lifetime follows a player, independent of item catalogue replacements. */
internal class CombatPlayerState {
    var aiming = false
    var aimingResetTask: Task? = null
    var reloadTask: Task? = null
    var placeTask: Task? = null
    var heldThrowable: Throwable? = null
    val throwableCooldowns = HashMap<String, Long>()

    val previousPositions = ArrayDeque<Pos>()
    var speed = 0f
    var lastActionTime = 0L

    /** Gun name to the time any copy may fire again after one was emptied, so swapping copies can't skip the reload. */
    val gunReloadLocks = HashMap<String, Long>()

    /** Whether the aim-while-sprinting speed and jump modifiers are applied. */
    var aimSprintBlocked = false
    var meleeLastAttackTime = 0L
    var lastMainHandType: String? = null
    var fallDistance = 0.0
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

    fun clearThrowable() {
        heldThrowable = null
    }

    /** Catalogue changes cancel item actions without resetting movement, cooldowns or protection. */
    fun cancelActions() {
        cancelAimingReset()
        cancelReload()
        cancelPlacement()
        clearThrowable()
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

    fun clear() {
        try {
            cancelActions()
        } finally {
            states.clear()
        }
    }
}
