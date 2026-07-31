package net.aechronis.logger.listeners

import net.aechronis.logger.Logger
import net.aechronis.logger.commands.playerInspectMode
import net.aechronis.logger.objects.BlockAction
import net.aechronis.logger.objects.BlockLogEntry
import net.aechronis.logger.objects.show
import net.aechronis.logger.utils.ItemCodec
import net.minestom.server.MinecraftServer
import net.minestom.server.event.EventListener
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.event.trait.BlockEvent
import net.minestom.server.event.trait.CancellableEvent
import net.minestom.server.event.trait.PlayerEvent

object BlockListener {
    private fun <E> inspect(event: E)
        where E : BlockEvent, E : CancellableEvent, E : PlayerEvent {
        val pos = event.blockPosition
        if (playerInspectMode[event.player.uuid] == true) {
            event.isCancelled = true
            show(event.player, pos.blockX(), pos.blockY(), pos.blockZ())
        }
    }

    private fun onBreak(event: PlayerBlockBreakEvent) {
        val pos = event.blockPosition
        val blockKey = event.block.key().asString()
        val resultBlock =
            if (event.isCancelled) {
                event.instance
                    .getBlock(pos)
                    .takeUnless { it.state() == event.block.state() && it.nbt() == event.block.nbt() }
                    ?: return
            } else {
                event.resultBlock
            }
        val instanceUuid = event.instance.uuid

        record(
            BlockLogEntry(
                timestamp = System.currentTimeMillis(),
                playerUuid = event.player.uuid,
                playerName = event.player.username,
                x = pos.blockX(),
                y = pos.blockY(),
                z = pos.blockZ(),
                blockOld = blockKey,
                blockNew = resultBlock.key().asString(),
                action = BlockAction.BREAK,
                instanceUuid = instanceUuid,
                blockOldState = event.block.state(),
                blockNewState = resultBlock.state(),
                blockOldNbt = ItemCodec.encodeBlockNbt(event.block.nbt()),
                blockNewNbt = ItemCodec.encodeBlockNbt(resultBlock.nbt()),
            ),
        )
    }

    private fun onPlace(event: PlayerBlockPlaceEvent) {
        if (event.isCancelled) return
        val pos = event.blockPosition
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
                instanceUuid = event.instance.uuid,
                blockOldState = previous.state(),
                blockNewState = event.block.state(),
                blockOldNbt = ItemCodec.encodeBlockNbt(previous.nbt()),
                blockNewNbt = ItemCodec.encodeBlockNbt(event.block.nbt()),
            ),
        )
    }

    private fun onInteract(event: PlayerBlockInteractEvent) {
        if (event.isCancelled) return
        val pos = event.blockPosition
        val blockKey = event.block.key().asString()

        LastInteractionTracker.record(event.player.uuid, pos.blockX(), pos.blockY(), pos.blockZ(), blockKey)

        if (!isLoggedInteraction(blockKey)) return

        record(
            BlockLogEntry(
                timestamp = System.currentTimeMillis(),
                playerUuid = event.player.uuid,
                playerName = event.player.username,
                x = pos.blockX(),
                y = pos.blockY(),
                z = pos.blockZ(),
                blockOld = blockKey,
                blockNew = blockKey,
                action = BlockAction.INTERACT,
                instanceUuid = event.instance.uuid,
                blockOldState = event.block.state(),
                blockNewState = event.block.state(),
            ),
        )
    }

    private val loggedInteractSuffixes = listOf("_door", "_trapdoor", "_fence_gate")

    private fun isLoggedInteraction(blockKey: String): Boolean = loggedInteractSuffixes.any { blockKey.endsWith(it) }

    private fun record(entry: BlockLogEntry) {
        Logger.repository.insertAsync(entry).exceptionally { exception ->
            println("failed to record block log entry: $exception")
            null
        }
    }

    fun init() {
        Logger.eventNode.addListener(PlayerBlockBreakEvent::class.java) { inspect(it) }
        Logger.eventNode.addListener(PlayerBlockPlaceEvent::class.java) { inspect(it) }
        Logger.eventNode.addListener(PlayerBlockInteractEvent::class.java) { inspect(it) }

        val postNode = EventNode.all("logger-block-post").setPriority(Int.MAX_VALUE)
        MinecraftServer.getGlobalEventHandler().addChild(postNode)
        postNode.addListener(
            EventListener
                .builder(PlayerBlockBreakEvent::class.java)
                .ignoreCancelled(false)
                .handler(::onBreak)
                .build(),
        )
        postNode.addListener(PlayerBlockPlaceEvent::class.java, ::onPlace)
        postNode.addListener(PlayerBlockInteractEvent::class.java, ::onInteract)
    }
}
