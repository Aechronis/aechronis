package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Vehicle
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent

object VehiclePlaceListener {
    fun onPlayerUseItemOnBlock(event: PlayerUseItemOnBlockEvent) {
        val player = event.player
        val vehicle = Item.getFromItemStack(player.itemInMainHand) as? Vehicle ?: return
        vehicle.place(player, event.position.asPos().add(0.5, 1.0, 0.5))
    }

    fun init() {
        Combat.eventNode.addListener(PlayerUseItemOnBlockEvent::class.java, VehiclePlaceListener::onPlayerUseItemOnBlock)
    }
}
