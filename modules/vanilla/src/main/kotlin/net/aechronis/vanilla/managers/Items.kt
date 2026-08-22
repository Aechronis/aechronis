package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.ItemListener
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.ItemEntity
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import java.time.Duration

object Items {
    fun init() {
        val timeStart = System.currentTimeMillis()
        ItemListener.init()
        val timeEnd = System.currentTimeMillis()
        println("├─ Items enabled in ${timeEnd - timeStart}ms")
    }

    fun spawn(
        instance: Instance,
        position: Pos,
        stack: ItemStack,
        velocity: Vec = Vec.ZERO,
        pickupDelayMs: Long = Vanilla.config.itemPickupDelayMs,
    ): ItemEntity {
        val config = Vanilla.config
        val item = ItemEntity(stack.withMaxStackSize(stack.material().maxStackSize()))
        item.setPickupDelay(Duration.ofMillis(pickupDelayMs))
        item.setInstance(instance, position)
        item.velocity = velocity
        item.scheduleRemove(Duration.ofSeconds(config.dropDespawnSeconds))
        return item
    }

    fun pickup(
        player: Player,
        stack: ItemStack,
    ): Boolean {
        if (player.gameMode == GameMode.SPECTATOR) return false
        return player.inventory.addItemStack(stack)
    }
}
