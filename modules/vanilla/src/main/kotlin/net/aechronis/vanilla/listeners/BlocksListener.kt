package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Blocks
import net.aechronis.vanilla.objects.consumeStationInteraction
import net.minestom.server.event.inventory.InventoryButtonClickEvent
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.inventory.Inventory
import net.minestom.server.item.ItemStack

object BlocksListener {
    private fun onButton(event: InventoryButtonClickEvent) {
        val player = event.player
        val open = event.inventory as? Inventory ?: return
        if (open !in Blocks.stonecutters) return

        val input = open.getItemStack(0)
        if (input.isAir) return

        val outputs = Blocks.outputsByInput[input.material()] ?: return
        val output = outputs.getOrNull(event.buttonId) ?: return

        val total = input.amount()
        open.setItemStack(0, ItemStack.AIR)

        val maxStack = ItemStack.of(output).maxStackSize()
        var remaining = total
        while (remaining > 0) {
            val give = minOf(remaining, maxStack)
            val stack = ItemStack.of(output, give)
            if (!player.inventory.addItemStack(stack)) player.dropItem(stack)
            remaining -= give
        }
    }

    fun onInteract(event: PlayerBlockInteractEvent) {
        if (!event.block.compare(Block.STONECUTTER)) return
        if (!event.consumeStationInteraction()) return

        Blocks.openConverter(event.player)
    }

    fun onClose(event: InventoryCloseEvent) {
        val inv = event.inventory as? Inventory ?: return
        if (!Blocks.stonecutters.remove(inv)) return

        val input = inv.getItemStack(0)
        if (input.isAir) return
        inv.setItemStack(0, ItemStack.AIR)
        if (!event.player.inventory.addItemStack(input)) event.player.dropItem(input)
    }

    fun init() {
        Vanilla.eventNode.addListener(InventoryButtonClickEvent::class.java, BlocksListener::onButton)
        Vanilla.eventNode.addListener(InventoryCloseEvent::class.java, BlocksListener::onClose)
        Vanilla.eventNode.addListener(PlayerBlockInteractEvent::class.java, BlocksListener::onInteract)
    }
}
