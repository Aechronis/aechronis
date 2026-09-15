package net.aechronis.server.listeners

import net.aechronis.combat.listeners.MannequinDamageListener
import net.aechronis.combat.objects.Vehicle
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleEvents
import net.aechronis.server.objects.Drone
import net.minestom.server.MinecraftServer
import net.minestom.server.event.EventNode
import net.minestom.server.event.entity.EntityDamageEvent
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerMoveEvent

object DroneListener {
    fun onScroll(event: PlayerChangeHeldSlotEvent) {
        val player = event.player
        if (Vehicle.drivenBy(player) !is Drone) return

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
        if (Vehicle.drivenBy(player) !is Drone) return

        val yaw = Drone.playerLockYaw[player] ?: return
        val pitch = Drone.playerLockPitch[player] ?: return
        event.newPosition = event.newPosition.withView(yaw, pitch)
    }

    private fun onOperatorDamage(event: EntityDamageEvent) {
        val pilot = Drone.mannequinPilot[event.entity] ?: return
        event.isCancelled = true
        MannequinDamageListener.forwardDamage(pilot, event.damage)
    }

    fun init(context: ModuleContext) {
        context.addListener(PlayerChangeHeldSlotEvent::class.java, DroneListener::onScroll)
        context.addListener(PlayerMoveEvent::class.java, DroneListener::onMove)
        context.addListener(PlayerDisconnectEvent::class.java) { event ->
            Drone.clearCrashStatic(event.player, resetCamera = false)
        }

        // Forward operator hits before combat applies generic mannequin and occupant protection.
        val damageNode = EventNode.all("a-new-millenium-drone-damage").setPriority(-1000)
        damageNode.addListener(EntityDamageEvent::class.java, DroneListener::onOperatorDamage)
        ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), damageNode)
    }
}
