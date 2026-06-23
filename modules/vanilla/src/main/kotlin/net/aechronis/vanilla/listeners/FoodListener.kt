package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Food
import net.minestom.server.entity.GameMode
import net.minestom.server.event.item.PlayerFinishItemUseEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.item.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

object FoodListener {
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

    private val wasOnGround = ConcurrentHashMap<UUID, Boolean>()

    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) return

        val uuid = player.uuid
        val nowOnGround = event.isOnGround
        val prevOnGround = wasOnGround.put(uuid, nowOnGround) ?: nowOnGround

        if (prevOnGround && !nowOnGround && event.newPosition.y() > player.position.y()) {
            Food.addExhaustion(player, if (player.isSprinting) 0.2f else 0.05f)
        }

        if (player.isSprinting && nowOnGround) {
            val dx = (event.newPosition.x() - player.position.x()).toFloat()
            val dz = (event.newPosition.z() - player.position.z()).toFloat()
            val dist = sqrt((dx * dx + dz * dz).toDouble()).toFloat()
            if (dist > 0f) Food.addExhaustion(player, dist * 0.1f)
        }
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        wasOnGround.remove(event.player.uuid)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerMoveEvent::class.java, FoodListener::onMove)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, FoodListener::onDisconnect)
        Vanilla.eventNode.addListener(PlayerUseItemEvent::class.java, FoodListener::onUseItem)
        Vanilla.eventNode.addListener(PlayerFinishItemUseEvent::class.java, FoodListener::onEat)
    }
}
