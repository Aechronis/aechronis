package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.managers.Corpses
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.collision.BoundingBox
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityPose
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.PlayerHand
import net.minestom.server.entity.metadata.avatar.MannequinMeta
import net.minestom.server.event.entity.EntityAttackEvent
import net.minestom.server.event.inventory.InventoryItemChangeEvent
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.network.player.ResolvableProfile
import net.minestom.server.timer.TaskSchedule
import net.minestom.server.utils.inventory.PlayerInventoryUtils

object CorpseListener {
    fun onInteract(event: PlayerEntityInteractEvent) {
        if (event.hand != PlayerHand.MAIN) return
        val target = event.target as? EntityCreature ?: return
        if (target.entityType != EntityType.MANNEQUIN) return
        val inv = Corpses.inventories[target] ?: return
        if (event.player.isSneaking) {
            Corpses.claim(target, event.player)
        } else {
            event.player.openInventory(inv)
        }
    }

    fun onAttack(event: EntityAttackEvent) {
        val player = event.entity as? net.minestom.server.entity.Player ?: return
        if (!player.isSneaking) return
        val target = event.target as? EntityCreature ?: return
        if (target.entityType != EntityType.MANNEQUIN) return
        Corpses.claim(target, player)
    }

    fun onInventoryChange(event: InventoryItemChangeEvent) {
        val inventory = event.inventory as? Inventory ?: return
        if (Corpses.despawnIfEmpty(inventory)) return
        Corpses.syncArmor(inventory)
    }

    fun onDeath(event: PlayerDeathEvent) {
        val player = event.player
        val instance = player.instance ?: return
        val lootSlots = (0..<player.inventory.size).filter { it != PlayerInventoryUtils.CRAFT_RESULT }
        val cursorItem = player.inventory.cursorItem
        if (cursorItem.isAir && lootSlots.all { player.inventory.getItemStack(it).isAir }) return

        val corpse = EntityCreature(EntityType.MANNEQUIN)
        // Offline/dev players can have no skin; keep the default mannequin look instead of failing the death.
        player.skin?.let { skin ->
            corpse.editEntityMeta(MannequinMeta::class.java) { meta ->
                meta.profile = ResolvableProfile(skin)
            }
        }

        val loot = Corpses.newLootInventory(player.username)
        for (slot in lootSlots) {
            val stack = player.inventory.getItemStack(slot)
            if (!stack.isAir) loot.setItemStack(slot, stack)
        }
        // The larger corpse inventory has spare slots after the player's mapped equipment slots.
        if (!cursorItem.isAir) loot.setItemStack(player.inventory.size, cursorItem)
        Corpses.register(corpse, loot)
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

        ModuleScheduler
            .buildTask {
                Corpses.despawn(loot)
            }.delay(TaskSchedule.seconds(Combat.config.corpseDespawnSeconds.toLong()))
            .schedule()
    }

    fun init() {
        Combat.eventNode.addListener(PlayerDeathEvent::class.java, CorpseListener::onDeath)
        Combat.eventNode.addListener(EntityAttackEvent::class.java, CorpseListener::onAttack)
        Combat.eventNode.addListener(InventoryItemChangeEvent::class.java, CorpseListener::onInventoryChange)
        Combat.eventNode.addListener(PlayerEntityInteractEvent::class.java, CorpseListener::onInteract)
    }
}
