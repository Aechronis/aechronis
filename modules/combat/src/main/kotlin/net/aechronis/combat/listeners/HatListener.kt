package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.constants.Tags
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

        if (playerMannequin[player.uuid] != null) {
            if (event.isHoldingLeftKey) {
                HatCollection.decrementSelectedHat(player.uuid)
                playerMannequin[player.uuid]?.helmet = HatCollection.getSelectedHat(player.uuid)?.toItemStack()
            }

            if (event.isHoldingRightKey) {
                HatCollection.incrementSelectedHat(player.uuid)
                playerMannequin[player.uuid]?.helmet = HatCollection.getSelectedHat(player.uuid)?.toItemStack()
            }

            if (event.isHoldingJumpKey) {
                HatCollection.resetSelectedHat(player.uuid)
                playerMannequin[player.uuid]?.helmet = ItemStack.AIR
            }

            if (event.isHoldingShiftKey) {
                HatCollection.equip(player.uuid, HatCollection.getSelectedHat(player.uuid))

                player.gameMode = GameMode.SURVIVAL
                player.teleport(playerMannequin[player.uuid]?.position)
                player.spectate(player)
                playerCamera[player.uuid]?.remove()
                playerMannequin[player.uuid]?.remove()
            }
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
        if (event.itemStack.getTag(Tags.name) != null) {
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
