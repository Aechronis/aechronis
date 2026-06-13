package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Food
import net.minestom.server.event.item.PlayerFinishItemUseEvent
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.item.ItemStack

object FoodEatListener {
    fun onUseItem(event: PlayerUseItemEvent) {
        val foodItem = Food.foodItems[event.itemStack.material()] ?: return
        if (event.player.food >= 20 && !foodItem.canAlwaysEat) event.isCancelled = true
    }

    fun onEat(event: PlayerFinishItemUseEvent) {
        val player = event.player
        val foodItem = Food.foodItems[event.itemStack.material()] ?: return
        if (player.food >= 20 && !foodItem.canAlwaysEat) return
        Food.onEat(player, foodItem)
        val item = player.getItemInHand(event.hand)
        if (!item.isAir) {
            player.setItemInHand(
                event.hand,
                if (item.amount() > 1) item.withAmount(item.amount() - 1) else ItemStack.AIR,
            )
        }
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerUseItemEvent::class.java, FoodEatListener::onUseItem)
        Vanilla.eventNode.addListener(PlayerFinishItemUseEvent::class.java, FoodEatListener::onEat)
    }
}
