package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Items
import net.aechronis.vanilla.managers.Music
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack

object MusicListener {
    fun onInteract(event: PlayerBlockInteractEvent) {
        if (event.hand != PlayerHand.MAIN) return
        if (!event.block.compare(Block.JUKEBOX)) return

        event.isCancelled = true
        event.isBlockingItemUse = true

        val position = event.blockPosition
        val inserted = Music.discIn(event.block)
        if (inserted != null) {
            val recordItem = event.block.getTag(Music.RECORD_ITEM_TAG) ?: return
            if (event.block.getTag(Music.PLAYING_TAG) == true) {
                Music.stop(event.instance, position, inserted)
                event.instance.setBlock(position, event.block.withTag(Music.PLAYING_TAG, false))
            } else {
                event.instance.setBlock(
                    position,
                    event.block.withTag(Music.RECORD_ITEM_TAG, null).withTag(Music.PLAYING_TAG, null),
                )
                if (!event.player.inventory.addItemStack(recordItem)) event.player.dropItem(recordItem)
            }
            return
        }

        val held = event.player.itemInMainHand
        val disc = Music.discFor(held) ?: return
        val item = held.withAmount(1)
        event.player.setItemInMainHand(if (held.amount() == 1) ItemStack.AIR else held.withAmount(held.amount() - 1))
        event.instance.setBlock(
            position,
            event.block.withTag(Music.RECORD_ITEM_TAG, item).withTag(Music.PLAYING_TAG, true),
        )
        Music.play(event.instance, position, disc, item)
    }

    fun onBreak(event: PlayerBlockBreakEvent) {
        if (!event.block.compare(Block.JUKEBOX)) return

        event.isCancelled = true
        val instance = event.player.instance ?: return
        val position = event.blockPosition
        val disc = Music.discIn(event.block)
        if (disc != null) Music.stop(instance, position, disc)
        instance.setBlock(position, Block.AIR)

        val dropPosition = position.add(0.5, 0.5, 0.5).asPos()
        Items.spawn(instance, dropPosition, ItemStack.of(net.minestom.server.item.Material.JUKEBOX))
        val record = event.block.getTag(Music.RECORD_ITEM_TAG)
        if (record != null) Items.spawn(instance, dropPosition, record)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerBlockInteractEvent::class.java, MusicListener::onInteract)
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, MusicListener::onBreak)
    }
}
