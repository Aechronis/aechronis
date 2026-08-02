package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Items
import net.aechronis.vanilla.managers.Storage
import net.aechronis.vanilla.objects.StorageContents
import net.aechronis.vanilla.objects.consumeStationInteraction
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryItemChangeEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.inventory.Inventory
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

object StorageListener {
    fun onBreak(event: PlayerBlockBreakEvent) {
        if (event.isCancelled) return
        if (!event.block.compare(Block.BARREL)) return

        val player = event.player
        val instance = player.instance ?: return
        val pos = event.blockPosition
        val key = Storage.keyFor(instance, pos)
        val contents = Storage.loadOrCreate(key)

        event.isCancelled = true
        contents.inventory.viewers
            .toList()
            .forEach { it.closeInventory() }
        instance.setBlock(pos, Block.AIR)

        val dropPos = pos.add(0.5, 0.5, 0.5).asPos()
        Items.spawn(instance, dropPos, ItemStack.of(Material.BARREL))
        for (slot in 0..<contents.inventory.size) {
            val stack = contents.inventory.getItemStack(slot)
            if (!stack.isAir) Items.spawn(instance, dropPos, stack)
        }

        Storage.remove(key)
    }

    fun onPlace(event: PlayerBlockPlaceEvent) {
        if (event.isCancelled) return
        if (!event.block.compare(Block.BARREL)) return

        val contents = StorageContents()

        event.block = Storage.withContents(event.block, contents)
    }

    fun onInteract(event: PlayerBlockInteractEvent) {
        if (!event.block.compare(Block.BARREL)) return
        if (!event.consumeStationInteraction()) return

        val key = Storage.keyFor(event.instance, event.blockPosition)
        val contents = Storage.loadOrCreate(key)
        event.player.openInventory(contents.inventory)
    }

    fun onInvClose(event: InventoryCloseEvent) {
        val closed = event.inventory
        if (closed !is Inventory) return
        val key = Storage.inventoryToKey[closed] ?: return
        Storage.save(key)
    }

    fun onInvChange(event: InventoryItemChangeEvent) {
        val inventory = event.inventory as? Inventory ?: return
        val key = Storage.inventoryToKey[inventory] ?: return
        Storage.save(key)
    }

    fun init() {
        Vanilla.eventNode.addListener(InventoryCloseEvent::class.java, StorageListener::onInvClose)
        Vanilla.eventNode.addListener(InventoryItemChangeEvent::class.java, StorageListener::onInvChange)
        Vanilla.eventNode.addListener(PlayerBlockInteractEvent::class.java, StorageListener::onInteract)
        Vanilla.eventNode.addListener(PlayerBlockPlaceEvent::class.java, StorageListener::onPlace)
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, StorageListener::onBreak)
    }
}
