package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Drone
import net.aechronis.combat.objects.Vehicle
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent
import net.minestom.server.event.player.PlayerMoveEvent

object DroneListener {

    fun onScroll(event: PlayerChangeHeldSlotEvent) {
        val player = event.player
        if (Vehicle.playerVehicle[player] !is Drone) return

        // shortest signed distance around the 0..8 hotbar ring, so scrolling
        // past an edge (e.g. 0 -> 8) counts as -1 rather than +8
        var delta = event.newSlot - event.oldSlot
        if (delta > 4) delta -= 9
        if (delta < -4) delta += 9

        Drone.playerThrottle[player] =
            ((Drone.playerThrottle[player] ?: 0F) + delta * -10F).coerceIn(0F, 100F)
    }

    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        if (Vehicle.playerVehicle[player] !is Drone) return

        val yaw = Drone.playerLockYaw[player] ?: return
        val pitch = Drone.playerLockPitch[player] ?: return
        event.newPosition = event.newPosition.withView(yaw, pitch)
    }

    fun init() {
        Combat.eventNode.addListener(PlayerChangeHeldSlotEvent::class.java, DroneListener::onScroll)
        Combat.eventNode.addListener(PlayerMoveEvent::class.java, DroneListener::onMove)
    }
}
