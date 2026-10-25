package net.aechronis.music.listeners

import net.aechronis.server.modules.ModuleEvents
import net.aechronis.vanilla.managers.Items
import net.aechronis.vanilla.managers.Jukeboxes
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

object JukeboxListener {
    // Interactions run after Vanilla's reach checks; breaking runs before generic block drops.
    private val eventNode = EventNode.all("music-jukeboxes").setPriority(1)
    private val breakEventNode = EventNode.all("music-jukebox-breaking").setPriority(-1)

    fun onInteract(event: PlayerBlockInteractEvent) {
        if (!Jukeboxes.enabled || event.isCancelled || event.hand != PlayerHand.MAIN || !event.block.compare(Block.JUKEBOX)) return
        event.isCancelled = true
        event.isBlockingItemUse = true

        val position = event.blockPosition
        val recordItem = event.block.getTag(Jukeboxes.RECORD_ITEM_TAG)
        if (recordItem != null) {
            if (event.block.getTag(Jukeboxes.PLAYING_TAG) == true && Jukeboxes.songFor(recordItem) != null) {
                Jukeboxes.stop(event.instance, position, event.block)
            } else {
                event.instance.setBlock(
                    position,
                    event.block.withTag(Jukeboxes.RECORD_ITEM_TAG, null).withTag(Jukeboxes.PLAYING_TAG, null),
                )
                if (!event.player.inventory.addItemStack(recordItem)) event.player.dropItem(recordItem)
            }
            return
        }

        val held = event.player.itemInMainHand
        val song = Jukeboxes.songFor(held) ?: return
        val item = held.withAmount(1)
        event.player.setItemInMainHand(if (held.amount() == 1) ItemStack.AIR else held.withAmount(held.amount() - 1))
        event.instance.setBlock(
            position,
            event.block.withTag(Jukeboxes.RECORD_ITEM_TAG, item).withTag(Jukeboxes.PLAYING_TAG, true),
        )
        Jukeboxes.play(event.instance, position, song, item)
    }

    fun onBreak(event: PlayerBlockBreakEvent) {
        if (!Jukeboxes.enabled || event.isCancelled || !event.block.compare(Block.JUKEBOX)) return
        val blockCenter = event.blockPosition.asVec().add(0.5, 0.5, 0.5)
        if (event.player.position.distanceSquared(blockCenter) > 36.0) {
            event.isCancelled = true
            return
        }
        event.isCancelled = true
        val instance = event.player.instance ?: return
        val position = event.blockPosition
        Jukeboxes.stop(instance, position, event.block)
        instance.setBlock(position, Block.AIR)

        val dropPosition = position.add(0.5, 0.5, 0.5).asPos()
        Items.spawn(instance, dropPosition, ItemStack.of(Material.JUKEBOX))
        event.block.getTag(Jukeboxes.RECORD_ITEM_TAG)?.let { Items.spawn(instance, dropPosition, it) }
    }

    fun init() {
        eventNode.addListener(PlayerBlockInteractEvent::class.java, ::onInteract)
        breakEventNode.addListener(PlayerBlockBreakEvent::class.java, ::onBreak)
        ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), eventNode)
        ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), breakEventNode)
    }
}
