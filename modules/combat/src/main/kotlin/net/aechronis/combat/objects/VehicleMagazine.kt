package net.aechronis.combat.objects

import net.aechronis.combat.utils.Message
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.title.Title
import net.minestom.server.entity.Player

/** Ammunition belongs to a weapon station; its current operator supplies reserve rounds. */
internal class VehicleMagazine(
    private val capacity: Int,
) {
    init {
        require(capacity > 0) { "Vehicle magazine capacity must be greater than zero" }
    }

    var ammo: Int = capacity
        private set
    var reloadStartedAt: Long? = null
        private set
    var nextShotAt: Long = 0L
        private set

    fun restoreAmmo(amount: Int) {
        ammo = amount.coerceIn(0, capacity)
    }

    /** Crew changes cancel reserve reloading, but preserve loaded rounds and shot recovery. */
    fun cancelReload() {
        reloadStartedAt = null
    }

    fun isWaiting(now: Long): Boolean = reloadStartedAt != null || nextShotAt > now

    fun consume(
        now: Long,
        hasReserve: Boolean,
        shotRecovery: Long? = null,
    ): Boolean {
        if (ammo <= 0) return false
        ammo -= 1
        if (shotRecovery != null) nextShotAt = now + shotRecovery
        if (ammo == 0 && hasReserve) reloadStartedAt = now
        return true
    }

    fun updateReload(
        now: Long,
        duration: Long,
        hasReserve: Boolean,
        consumeReserve: () -> Unit,
    ): ReloadUpdate {
        if (ammo > 0) {
            return if (nextShotAt > now) {
                ReloadUpdate.Progress((1.0 - (nextShotAt - now).toDouble() / duration.coerceAtLeast(1)).coerceIn(0.0, 1.0))
            } else {
                ReloadUpdate.Idle
            }
        }
        if (!hasReserve) {
            val cancelled = reloadStartedAt != null
            cancelReload()
            return if (cancelled) ReloadUpdate.Cancelled else ReloadUpdate.Idle
        }
        val startedAt = reloadStartedAt ?: now.also { reloadStartedAt = it }
        val elapsed = now - startedAt
        if (elapsed < duration) return ReloadUpdate.Progress((elapsed.toDouble() / duration).coerceIn(0.0, 1.0))
        consumeReserve()
        ammo = capacity
        cancelReload()
        return ReloadUpdate.Completed
    }

    sealed interface ReloadUpdate {
        data object Idle : ReloadUpdate

        data object Cancelled : ReloadUpdate

        data object Completed : ReloadUpdate

        data class Progress(
            val fraction: Double,
        ) : ReloadUpdate
    }
}

internal fun updateVehicleReload(
    player: Player,
    ride: VehicleRide,
    magazine: VehicleMagazine,
    ammo: Ammo,
    duration: Long,
    now: Long,
) {
    val hasReserve = magazine.ammo == 0 && ammo[player] > 0
    when (val update = magazine.updateReload(now, duration, hasReserve) { ammo[player] -= 1 }) {
        VehicleMagazine.ReloadUpdate.Idle -> Unit
        VehicleMagazine.ReloadUpdate.Cancelled -> player.clearTitle()
        VehicleMagazine.ReloadUpdate.Completed -> {
            ride.clearEmptyAmmoFeedback()
            player.clearTitle()
        }
        is VehicleMagazine.ReloadUpdate.Progress ->
            player.showTitle(
                Title.title(Component.empty(), Message.progressBar(update.fraction).shadowColor(ShadowColor.none()), 0, 3, 10),
            )
    }
}

internal fun hasReadyVehicleAmmo(
    player: Player,
    ride: VehicleRide,
    magazine: VehicleMagazine,
    ammo: Ammo,
    now: Long = System.currentTimeMillis(),
): Boolean {
    if (magazine.isWaiting(now)) return false
    if (magazine.ammo > 0) return true
    if (ammo[player] == 0 && ride.canReportEmptyAmmo(now)) {
        player.showTitle(
            Title.title(
                Component.empty(),
                Component.text("✕").color(TextColor.color(0.5F, 0F, 0F)).shadowColor(ShadowColor.none()),
                0,
                10,
                10,
            ),
        )
    }
    return false
}
