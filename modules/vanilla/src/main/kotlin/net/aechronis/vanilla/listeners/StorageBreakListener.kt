package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Storage
import net.aechronis.vanilla.utils.Notifications
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.instance.block.Block

object StorageBreakListener {
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

    fun init() {
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, StorageBreakListener::onBreak)
    }
}
