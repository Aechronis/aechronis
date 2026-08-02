package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.minestom.server.event.player.PlayerHandAnimationEvent

object FireListener {
    private fun onPlayerHandAnimation(event: PlayerHandAnimationEvent) {
        val player = event.player
        val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun ?: return

        gun.fire(player)
    }

    fun init() {
        Combat.eventNode.addListener(PlayerHandAnimationEvent::class.java, FireListener::onPlayerHandAnimation)
    }
}
