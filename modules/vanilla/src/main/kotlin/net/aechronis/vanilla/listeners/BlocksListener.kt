package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Blocks
import net.aechronis.vanilla.objects.consumeStationInteraction
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryItemChangeEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.click.Click
import net.minestom.server.item.ItemStack

object BlocksListener {
    private fun onClick(event: InventoryPreClickEvent) {
        val open = event.player.openInventory as? Inventory ?: return
        if (open !in Blocks.stonecutters) return

        // A shift-click from the player inventory would otherwise move an item into an
        // arbitrary decorative slot in this chest. Normal clicks remain available for placing
        // an input stack in the converter.
        if (event.inventory !== open) {
            if (event.click is Click.LeftShift || event.click is Click.RightShift) event.isCancelled = true
            return
        }

        // The input slot is the only slot which uses normal inventory behaviour. Output and
        // navigation slots are server-controlled, so never let the client insert items there.
        if (event.slot == Blocks.INPUT_SLOT) return

        event.isCancelled = true
        when (event.slot) {
            Blocks.PREVIOUS_PAGE_SLOT -> Blocks.setConverterPage(open, (Blocks.currentConverterPage(open) - 1).coerceAtLeast(0))
            Blocks.NEXT_PAGE_SLOT -> Blocks.setConverterPage(open, Blocks.currentConverterPage(open) + 1)
            in Blocks.OUTPUT_START_SLOT..Blocks.OUTPUT_END_SLOT ->
                Blocks.outputFor(open, event.slot)?.let {
                    Blocks.convert(event.player, open, it)
                }
        }
    }

    private fun onInputChange(event: InventoryItemChangeEvent) {
        val open = event.inventory as? Inventory ?: return
        if (open !in Blocks.stonecutters || event.slot != Blocks.INPUT_SLOT) return
        Blocks.refreshConverter(open, resetPage = true)
    }

    fun onInteract(event: PlayerBlockInteractEvent) {
        if (!event.block.compare(Block.STONECUTTER)) return
        if (!event.consumeStationInteraction()) return

        Blocks.openConverter(event.player)
    }

    fun onClose(event: InventoryCloseEvent) {
        val inv = event.inventory as? Inventory ?: return
        if (inv !in Blocks.stonecutters) return

        val input = inv.getItemStack(Blocks.INPUT_SLOT)
        Blocks.closeConverter(inv)
        if (input.isAir) return
        inv.setItemStack(Blocks.INPUT_SLOT, ItemStack.AIR)
        if (!event.player.inventory.addItemStack(input)) event.player.dropItem(input)
    }

    fun init() {
        Vanilla.eventNode.addListener(InventoryPreClickEvent::class.java, BlocksListener::onClick)
        Vanilla.eventNode.addListener(InventoryItemChangeEvent::class.java, BlocksListener::onInputChange)
        Vanilla.eventNode.addListener(InventoryCloseEvent::class.java, BlocksListener::onClose)
        Vanilla.eventNode.addListener(PlayerBlockInteractEvent::class.java, BlocksListener::onInteract)
    }
}
