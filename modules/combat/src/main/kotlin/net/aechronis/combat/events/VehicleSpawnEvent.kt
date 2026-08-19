package net.aechronis.combat.events

import net.aechronis.combat.objects.Vehicle
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.event.trait.CancellableEvent
import net.minestom.server.instance.Instance

/** Fired before a player starts placing a vehicle. */
class VehicleSpawnEvent(
    val player: Player,
    val vehicle: Vehicle,
    val instance: Instance,
    val position: Pos,
) : CancellableEvent {
    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }
}
