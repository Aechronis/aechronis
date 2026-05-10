package net.aechronis.logger.listeners

import net.aechronis.logger.BlockAction
import net.aechronis.logger.BlockLogEntry
import net.aechronis.logger.Logger
import net.aechronis.logger.commands.playerInspectMode
import net.aechronis.logger.inspect.show
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent

object BlockListener {
    private fun onBreak(event: PlayerBlockBreakEvent) {
        val pos = event.blockPosition

        if (playerInspectMode[event.player.uuid] == true) {
            event.isCancelled = true
            show(event.player, pos.blockX(), pos.blockY(), pos.blockZ())
            return
        }

        record(
            BlockLogEntry(
                timestamp = System.currentTimeMillis(),
                playerUuid = event.player.uuid,
                playerName = event.player.username,
                x = pos.blockX(),
                y = pos.blockY(),
                z = pos.blockZ(),
                blockOld = event.block.key().asString(),
                blockNew = "minecraft:air",
                action = BlockAction.BREAK,
            ),
        )
    }

    private fun onPlace(event: PlayerBlockPlaceEvent) {
        val pos = event.blockPosition

        if (playerInspectMode[event.player.uuid] == true) {
            event.isCancelled = true
            show(event.player, pos.blockX(), pos.blockY(), pos.blockZ())
            return
        }

        val previous = event.instance.getBlock(pos)
        record(
            BlockLogEntry(
                timestamp = System.currentTimeMillis(),
                playerUuid = event.player.uuid,
                playerName = event.player.username,
                x = pos.blockX(),
                y = pos.blockY(),
                z = pos.blockZ(),
                blockOld = previous.key().asString(),
                blockNew = event.block.key().asString(),
                action = BlockAction.PLACE,
            ),
        )
    }

    private fun onInteract(event: PlayerBlockInteractEvent) {
        val pos = event.blockPosition

        if (playerInspectMode[event.player.uuid] == true) {
            event.isCancelled = true
            show(event.player, pos.blockX(), pos.blockY(), pos.blockZ())
            return
        }

        record(
            BlockLogEntry(
                timestamp = System.currentTimeMillis(),
                playerUuid = event.player.uuid,
                playerName = event.player.username,
                x = pos.blockX(),
                y = pos.blockY(),
                z = pos.blockZ(),
                blockOld = event.block.key().asString(),
                blockNew = event.block.key().asString(),
                action = BlockAction.INTERACT,
            ),
        )
    }

    private fun record(entry: BlockLogEntry) {
        Logger.repository.insertAsync(entry).exceptionally { exception ->
            println("failed to record block log entry: $exception")
            null
        }
    }

    fun init() {
        Logger.eventNode.addListener(PlayerBlockBreakEvent::class.java, ::onBreak)
        Logger.eventNode.addListener(PlayerBlockPlaceEvent::class.java, ::onPlace)
        Logger.eventNode.addListener(PlayerBlockInteractEvent::class.java, ::onInteract)
    }
}