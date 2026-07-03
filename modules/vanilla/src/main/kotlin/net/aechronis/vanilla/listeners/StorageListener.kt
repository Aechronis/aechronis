package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Items
import net.aechronis.vanilla.managers.Storage
import net.aechronis.vanilla.objects.StorageContents
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.inventory.Inventory
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

object StorageListener {
    fun onBreak(event: PlayerBlockBreakEvent) {
        if (!event.block.compare(Block.BARREL)) return

        val player = event.player
        val instance = player.instance ?: return
        val pos = event.blockPosition

        event.isCancelled = true
        instance.setBlock(pos, Block.AIR)

        val key = Storage.keyFor(instance, pos.asVec())
        val contents = Storage.loadOrCreate(key)

        val dropPos = pos.add(0.5, 0.5, 0.5).asPos()
        Items.spawn(instance, dropPos, ItemStack.of(Material.BARREL))
        for (slot in 0..<contents.inventory.size) {
            val stack = contents.inventory.getItemStack(slot)
            if (!stack.isAir) Items.spawn(instance, dropPos, stack)
        }

        Storage.remove(key)
    }

    fun onPlace(event: PlayerBlockPlaceEvent) {
        if (!event.block.compare(Block.BARREL)) return

        val player = event.player
        val instance = player.instance ?: return
        val pos = event.blockPosition.asVec()
        val key = Storage.keyFor(instance, pos)

        Storage.register(key, StorageContents())
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
