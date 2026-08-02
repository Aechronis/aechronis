package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Mannequin
import net.minestom.server.MinecraftServer
import net.minestom.server.collision.BoundingBox
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityPose
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.PlayerHand
import net.minestom.server.entity.metadata.avatar.MannequinMeta
import net.minestom.server.event.entity.EntityDamageEvent
import net.minestom.server.event.inventory.InventoryItemChangeEvent
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.network.player.ResolvableProfile
import net.minestom.server.timer.TaskSchedule

object MannequinListener {
    fun onEntityDamage(event: EntityDamageEvent) {
        if (event.entity.entityType == EntityType.MANNEQUIN) event.isCancelled = true
    }

    fun onInteract(event: PlayerEntityInteractEvent) {
        if (event.hand != PlayerHand.MAIN) return
        val target = event.target as? EntityCreature ?: return
        if (target.entityType != EntityType.MANNEQUIN) return
        val inv = Mannequin.inventories[target] ?: return
        event.player.openInventory(inv)
    }

    fun onInventoryChange(event: InventoryItemChangeEvent) {
        val inventory = event.inventory as? Inventory ?: return
        if (Mannequin.despawnIfEmpty(inventory)) return
        Mannequin.syncArmor(inventory)
    }

    fun onDeath(event: PlayerDeathEvent) {
        val player = event.player
        val instance = player.instance ?: return
        if ((0..<player.inventory.size).all { player.inventory.getItemStack(it).isAir }) return

        val corpse = EntityCreature(EntityType.MANNEQUIN)
        corpse.editEntityMeta(MannequinMeta::class.java) { meta ->
            meta.profile = ResolvableProfile(player.skin)
        }

        val loot = Mannequin.newLootInventory(player.username)
        for (slot in 0..<player.inventory.size) {
            val stack = player.inventory.getItemStack(slot)
            if (!stack.isAir) loot.setItemStack(slot, stack)
        }
        Mannequin.register(corpse, loot)
        player.inventory.clear()

        corpse.setInstance(instance, player.position)
        corpse.boundingBox = BoundingBox(0.0, 0.0, 0.0)
        corpse.pose = EntityPose.SWIMMING

        val attacker = player.lastDamageSource?.attacker
        if (attacker != null) {
            corpse.velocity =
                attacker.position
                    .direction()
                    .mul(10.0)
                    .withY(2.0)
        }

        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                Mannequin.despawn(loot)
            }.delay(TaskSchedule.seconds(Vanilla.config.mannequinDespawnTime.toLong()))
            .schedule()
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerDeathEvent::class.java, MannequinListener::onDeath)
        Vanilla.eventNode.addListener(EntityDamageEvent::class.java, MannequinListener::onEntityDamage)
        Vanilla.eventNode.addListener(InventoryItemChangeEvent::class.java, MannequinListener::onInventoryChange)
        Vanilla.eventNode.addListener(PlayerEntityInteractEvent::class.java, MannequinListener::onInteract)
    }
}
