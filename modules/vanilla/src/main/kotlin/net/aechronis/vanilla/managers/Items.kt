package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.ItemListener
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.ItemEntity
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityDespawnEvent
import net.minestom.server.event.entity.EntitySpawnEvent
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import java.time.Duration

object Items {
    private val groundItems = mutableSetOf<ItemEntity>()

    fun init() {
        val timeStart = System.currentTimeMillis()
        ItemListener.init()
        Vanilla.eventNode.addListener(EntitySpawnEvent::class.java, ::onEntitySpawn)
        Vanilla.eventNode.addListener(EntityDespawnEvent::class.java, ::onEntityDespawn)
        MinecraftServer.getInstanceManager().instances.forEach { instance ->
            groundItems += instance.entities.filterIsInstance<ItemEntity>()
        }
        enforceGroundItemLimit()
        val timeEnd = System.currentTimeMillis()
        println("├─ Items enabled in ${timeEnd - timeStart}ms")
    }

    private fun onEntitySpawn(event: EntitySpawnEvent) {
        val item = event.entity as? ItemEntity ?: return
        groundItems += item
        enforceGroundItemLimit()
    }

    private fun onEntityDespawn(event: EntityDespawnEvent) {
        (event.entity as? ItemEntity)?.let(groundItems::remove)
    }

    private fun enforceGroundItemLimit() {
        val limit = Vanilla.config.groundItemLimit
        if (limit <= 0 || groundItems.size < limit) return

        val count = groundItems.size
        groundItems.toList().forEach(ItemEntity::remove)
        println("Mass-wiped $count ground items after reaching the $limit item limit")
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
