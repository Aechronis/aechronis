package luna.nodes.combat.listeners

import luna.nodes.combat.Combat
import luna.nodes.combat.objects.Gun
import luna.nodes.combat.objects.Item
import net.minestom.server.event.player.PlayerHandAnimationEvent
import net.minestom.server.event.player.PlayerSwapItemEvent
import net.minestom.server.event.player.PlayerUseItemEvent

object ReloadListener {
    fun onPlayerOffhand(event: PlayerSwapItemEvent) {
        val player = event.player
        val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun ?: return
        event.isCancelled = true
        if (gun.getAmmo(player) != gun.maxAmmo) {
            gun.reload(player)
        }
    }

    fun onPlayerHandAnimation(event: PlayerHandAnimationEvent) {
        val player = event.player
        val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun ?: return
        if (!gun.hasAmmo(player)) {
            gun.reload(player)
        }
    }

    fun onPlayerUseItem(event: PlayerUseItemEvent) {
        val player = event.player
        val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun ?: return
        if (!gun.hasAmmo(player)) {
            gun.reload(player)
        }
    }

    fun init() {
        Combat.eventNode.addListener(PlayerSwapItemEvent::class.java, ReloadListener::onPlayerOffhand)
        Combat.eventNode.addListener(PlayerHandAnimationEvent::class.java, ReloadListener::onPlayerHandAnimation)
        Combat.eventNode.addListener(PlayerUseItemEvent::class.java, ReloadListener::onPlayerUseItem)
    }
}
