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
        ItemListener.init()
    }

    fun spawn(
        instance: Instance,
        position: Pos,
        stack: ItemStack,
        velocity: Vec = Vec.ZERO,
        pickupDelayMs: Long = Vanilla.config.itemPickupDelayMs,
    ): ItemEntity {
        val item = ItemEntity(stack.withMaxStackSize(stack.material().maxStackSize()))
        item.setPickupDelay(Duration.ofMillis(pickupDelayMs))
        item.setInstance(instance, position)
        item.scheduleRemove(Duration.ofSeconds(Vanilla.config.itemDespawnSeconds))
        item.velocity = velocity
        return item
    }

    /**
     * Spawns an item that only a pickup removes. It also never merges, because a merge can fold it
     * into a nearby item that does despawn.
     */
    fun spawnPersistent(
        instance: Instance,
        position: Pos,
        stack: ItemStack,
    ): ItemEntity {
        val item = ItemEntity(stack.withMaxStackSize(stack.material().maxStackSize()))
        item.setPickupDelay(Duration.ofMillis(Vanilla.config.itemPickupDelayMs))
        item.isMergeable = false
        item.setInstance(instance, position)
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
