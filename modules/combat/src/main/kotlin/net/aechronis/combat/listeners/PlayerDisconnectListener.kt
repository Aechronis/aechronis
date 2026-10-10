package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.HatMenu
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.tasks.ModelManager
import net.aechronis.combat.tasks.VehicleTickManager
import net.aechronis.combat.utils.LagCompensation
import net.minestom.server.event.player.PlayerDisconnectEvent

object PlayerDisconnectListener {
    private fun onPlayerDisconnect(event: PlayerDisconnectEvent) {
        val player = event.player

        // vehicle
        Vehicle.exit(player)

        VehicleTickManager.playerLookingAtVehicle.remove(player)
        VehicleTickManager.playerLookingAtEntity.remove(player)
        VehicleTickManager.removePlayer(player)

        Combat.playerStates.remove(player)
        Combat.entityLastDamageTime.remove(player)
        LagCompensation.removePlayer(player)
        ModelManager.clearPlayer(player)
        KeyPressListener.playerInputEvent.remove(player)
        Hitbox.viewingHitboxes.remove(player)
        HatMenu.close(player)
    }

    fun init() {
        Combat.eventNode.addListener(PlayerDisconnectEvent::class.java, PlayerDisconnectListener::onPlayerDisconnect)
    }
}
