package net.aechronis.logger.listeners

import io.github.openminigameserver.worldedit.event.WorldEditBlockChangesEvent
import net.aechronis.logger.Logger
import net.aechronis.logger.objects.BlockAction
import net.aechronis.logger.objects.BlockLogEntry
import net.aechronis.logger.utils.ItemCodec
import net.aechronis.logger.utils.LogMetadata

object WorldEditListener {
    fun init() {
        Logger.eventNode.addListener(WorldEditBlockChangesEvent::class.java) { event ->
            val timestamp = System.currentTimeMillis()
            runCatching {
                event.changes.map { change ->
                    val position = change.position
                    val oldBlock = change.oldBlock
                    val newBlock = change.newBlock
                    BlockLogEntry(
                        timestamp = timestamp,
                        playerUuid = event.actorUuid,
                        playerName = event.actorName,
                        x = position.blockX(),
                        y = position.blockY(),
                        z = position.blockZ(),
                        blockOld = oldBlock.key().asString(),
                        blockNew = newBlock.key().asString(),
                        action = if (newBlock.isAir) BlockAction.BREAK else BlockAction.PLACE,
                        instanceUuid = event.instance.uuid,
                        blockOldState = oldBlock.state(),
                        blockNewState = newBlock.state(),
                        blockOldNbt = ItemCodec.encodeBlockNbt(oldBlock.nbt()),
                        blockNewNbt = ItemCodec.encodeBlockNbt(newBlock.nbt()),
                        source = LogMetadata.WORLDEDIT,
                        origin = LogMetadata.WORLDEDIT,
                    )
                }
            }.onSuccess { entries ->
                Logger.repository.insertAllAsync(entries).exceptionally { failure ->
                    println("[Logger] failed to record WorldEdit block changes: $failure")
                    null
                }
            }.onFailure { failure ->
                println("[Logger] failed to encode WorldEdit block changes: $failure")
            }
        }
    }
}
