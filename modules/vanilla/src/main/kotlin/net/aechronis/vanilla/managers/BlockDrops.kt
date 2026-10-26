package net.aechronis.vanilla.managers

import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import java.util.Collections
import java.util.WeakHashMap

/** Inventory delivery with overflow spawned at the player's feet. */
object BlockDrops {
    private val suppressed = Collections.synchronizedMap(WeakHashMap<PlayerBlockBreakEvent, Boolean>())

    /** Marks drops handled by another listener without cancelling the block break. */
    fun suppress(event: PlayerBlockBreakEvent) {
        suppressed[event] = true
    }

    internal fun consumeSuppression(event: PlayerBlockBreakEvent): Boolean = suppressed.remove(event) == true

    /** Puts [stack] in the inventory, and only falls back to the ground at the player's feet when it is full. */
    fun giveOrDrop(
        player: Player,
        instance: Instance,
        stack: ItemStack,
    ) {
        // Boosted drops can exceed one stack, so hand them over a stack at a time.
        var remaining = stack.amount()
        while (remaining > 0) {
            val part = stack.withAmount(minOf(remaining, stack.maxStackSize()))
            if (!player.inventory.addItemStack(part)) Items.spawn(instance, player.position, part)
            remaining -= part.amount()
        }
    }
}
