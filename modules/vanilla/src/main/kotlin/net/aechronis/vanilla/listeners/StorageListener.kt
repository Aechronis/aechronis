package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Storage
import net.aechronis.vanilla.objects.StorageContents
import net.aechronis.vanilla.serdes.StorageDeserializer
import net.aechronis.vanilla.utils.Notifications
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.inventory.Inventory

object StorageListener {
    fun onBreak(event: PlayerBlockBreakEvent) {
        if (!event.block.compare(Block.BARREL)) return

        val player = event.player
        val instance = player.instance ?: return
        val pos = event.blockPosition

        val key = Storage.keyFor(instance, pos.asVec())
        val contents = Storage.loadOrCreate(key)
        val barrelItem = Storage.buildBarrelItem(contents)

        if (!player.inventory.addItemStack(barrelItem)) {
            event.isCancelled = true
            player.sendNotification(
                Notifications.fullInv,
            )
            return
        }

        event.isCancelled = true
        Storage.remove(key)
        instance.setBlock(pos, Block.AIR)
    }

    fun onPlace(event: PlayerBlockPlaceEvent) {
        if (!event.block.compare(Block.BARREL)) return

        val player = event.player
        val instance = player.instance ?: return
        val placedItem = player.getItemInHand(event.hand)
        val storedTag = Storage.extractContentsTag(placedItem)

        val pos = event.blockPosition.asVec()
        val key = Storage.keyFor(instance, pos)

        val contents =
            if (storedTag != null) {
                StorageDeserializer.deserialize(storedTag)
            } else {
                StorageContents()
            }

        Storage.register(key, contents)
        Storage.save(key)
    }

    fun onInteract(event: PlayerBlockInteractEvent) {
        if (event.hand != PlayerHand.MAIN) return
        if (!event.block.compare(Block.BARREL)) return

        val player = event.player
        if (player.isSneaking && !player.itemInMainHand.isAir) return

        event.isCancelled = true
        val instance = player.instance ?: return
        val key = Storage.keyFor(instance, event.blockPosition.asVec())
        val contents = Storage.loadOrCreate(key)
        player.openInventory(contents.inventory)
    }

    fun onInvClose(event: InventoryCloseEvent) {
        val closed = event.inventory
        if (closed !is Inventory) return
        val key = Storage.inventoryToKey[closed] ?: return
        Storage.save(key)
    }

    fun init() {
        Vanilla.eventNode.addListener(InventoryCloseEvent::class.java, StorageListener::onInvClose)
        Vanilla.eventNode.addListener(PlayerBlockInteractEvent::class.java, StorageListener::onInteract)
        Vanilla.eventNode.addListener(PlayerBlockPlaceEvent::class.java, StorageListener::onPlace)
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, StorageListener::onBreak)
    }
}
