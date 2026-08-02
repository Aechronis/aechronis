package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Hat
import net.aechronis.combat.objects.Item
import net.aechronis.combat.storage.HatCollection
import net.minestom.server.entity.Entity
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.LivingEntity
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.item.ItemDropEvent
import net.minestom.server.event.player.PlayerInputEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.item.ItemStack
import java.util.UUID

object HatListener {
    val playerMannequin = HashMap<UUID, LivingEntity>()
    val playerCamera = HashMap<UUID, Entity>()

    private fun onPlayerSpawn(event: PlayerSpawnEvent) {
        if (!event.isFirstSpawn) return
        HatCollection.load(event.player.uuid)
    }

    // equip menu
    private fun onPlayerInput(event: PlayerInputEvent) {
        val player = event.player
        val uuid = player.uuid
        val mannequin = playerMannequin[uuid] ?: return

        if (event.isHoldingLeftKey) {
            HatCollection.cycleSelectedHat(uuid, false)
            mannequin.helmet = HatCollection.getSelectedHat(uuid)?.toItemStack()
        }

        if (event.isHoldingRightKey) {
            HatCollection.cycleSelectedHat(uuid, true)
            mannequin.helmet = HatCollection.getSelectedHat(uuid)?.toItemStack()
        }

        if (event.isHoldingJumpKey) {
            HatCollection.resetSelectedHat(uuid)
            mannequin.helmet = ItemStack.AIR
        }

        if (event.isHoldingShiftKey) {
            HatCollection.equip(uuid, HatCollection.getSelectedHat(uuid))

            player.gameMode = GameMode.SURVIVAL
            player.teleport(mannequin.position)
            player.spectate(player)
            playerCamera.remove(uuid)?.remove()
            playerMannequin.remove(uuid)?.remove()
        }
    }

    // prevent moving hats
    private fun onInventoryClick(event: InventoryPreClickEvent) {
        if ((Item.getFromItemStack(event.clickedItem) as? Hat) != null) {
            event.isCancelled = true
        }
    }

    // prevent dropping hats
    private fun onItemDrop(event: ItemDropEvent) {
        if ((Item.getFromItemStack(event.itemStack) as? Hat) != null) {
            event.isCancelled = true
        }
    }

    fun init() {
        Combat.eventNode.addListener(PlayerSpawnEvent::class.java, HatListener::onPlayerSpawn)
        Combat.eventNode.addListener(PlayerInputEvent::class.java, HatListener::onPlayerInput)
        Combat.eventNode.addListener(InventoryPreClickEvent::class.java, HatListener::onInventoryClick)
        Combat.eventNode.addListener(ItemDropEvent::class.java, HatListener::onItemDrop)
    }
}
