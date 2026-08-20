package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.utils.LagCompensation
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityTeleportEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.event.player.PlayerTickEndEvent

object LagCompensationListener {
    private fun onPlayerTick(event: PlayerTickEndEvent) {
        if (Vehicle.isVehicleOccupant(event.player)) {
            LagCompensation.resetHistory(event.player)
        } else {
            LagCompensation.recordPlayer(event.player)
        }
    }

    private fun onPlayerSpawn(event: PlayerSpawnEvent) {
        LagCompensation.resetHistory(event.player)
    }

    private fun onEntityTeleport(event: EntityTeleportEvent) {
        val player = event.entity as? Player ?: return
        LagCompensation.resetHistory(player)
    }

    fun init() {
        Combat.eventNode.addListener(PlayerTickEndEvent::class.java, LagCompensationListener::onPlayerTick)
        Combat.eventNode.addListener(PlayerSpawnEvent::class.java, LagCompensationListener::onPlayerSpawn)
        Combat.eventNode.addListener(EntityTeleportEvent::class.java, LagCompensationListener::onEntityTeleport)
    }
}
