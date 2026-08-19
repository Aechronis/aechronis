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
import net.minestom.server.item.Material

object BlocksListener {
    private fun incomingMaterial(event: InventoryPreClickEvent): Material? {
        val item =
            when (val click = event.click) {
                is Click.Left, is Click.Right -> event.player.inventory.cursorItem
                is Click.LeftDrag, is Click.RightDrag, is Click.MiddleDrag -> event.player.inventory.cursorItem
                is Click.HotbarSwap -> event.player.inventory.getItemStack(click.hotbarSlot)
                is Click.OffhandSwap -> event.player.itemInOffHand
                else -> ItemStack.AIR
            }
        return item.takeUnless(ItemStack::isAir)?.material()
    }

    private fun onClick(event: InventoryPreClickEvent) {
        val open = event.player.openInventory as? Inventory ?: return
        if (open !in Blocks.stonecutters) return

        // A shift-click from the player inventory would otherwise move an item into an
        // arbitrary decorative slot in this chest.
        if (event.inventory !== open) {
            if (event.click is Click.LeftShift || event.click is Click.RightShift) event.isCancelled = true
            return
        }

        when (event.slot) {
            in Blocks.INPUT_START_SLOT..Blocks.INPUT_END_SLOT -> {
                val incoming = incomingMaterial(event)
                if (incoming != null && !Blocks.canDeposit(open, incoming)) event.isCancelled = true
            }
            Blocks.CONVERT_BUTTON_SLOT -> {
                event.isCancelled = true
                Blocks.toggleConverterOptions(open)
            }
            Blocks.PREVIOUS_PAGE_SLOT -> {
                event.isCancelled = true
                Blocks.setConverterPage(open, (Blocks.currentConverterPage(open) - 1).coerceAtLeast(0))
            }
            Blocks.NEXT_PAGE_SLOT -> {
                event.isCancelled = true
                Blocks.setConverterPage(open, Blocks.currentConverterPage(open) + 1)
            }
            in Blocks.OUTPUT_START_SLOT..Blocks.OUTPUT_END_SLOT -> {
                event.isCancelled = true
                Blocks.outputFor(open, event.slot)?.let { Blocks.convert(event.player, open, it) }
            }
            else -> event.isCancelled = true
        }
    }

    private fun onInputChange(event: InventoryItemChangeEvent) {
        val open = event.inventory as? Inventory ?: return
        if (open !in Blocks.stonecutters) return
        if (event.slot !in Blocks.INPUT_START_SLOT..Blocks.INPUT_END_SLOT) return
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

        val input =
            (Blocks.INPUT_START_SLOT..Blocks.INPUT_END_SLOT)
                .map { slot -> slot to inv.getItemStack(slot) }
                .filterNot { (_, stack) -> stack.isAir }
        Blocks.closeConverter(inv)
        for ((slot, stack) in input) {
            inv.setItemStack(slot, ItemStack.AIR)
            if (!event.player.inventory.addItemStack(stack)) event.player.dropItem(stack)
        }
    }

    fun init() {
        Vanilla.eventNode.addListener(InventoryPreClickEvent::class.java, BlocksListener::onClick)
        Vanilla.eventNode.addListener(InventoryItemChangeEvent::class.java, BlocksListener::onInputChange)
        Vanilla.eventNode.addListener(InventoryCloseEvent::class.java, BlocksListener::onClose)
        Vanilla.eventNode.addListener(PlayerBlockInteractEvent::class.java, BlocksListener::onInteract)
    }
}
