package net.aechronis.logger.listeners

import io.github.openminigameserver.worldedit.event.WorldEditBlockChangesEvent
import net.aechronis.logger.Logger
import net.aechronis.logger.objects.BlockAction
import net.aechronis.logger.objects.BlockLogEntry
import net.aechronis.logger.utils.ItemCodec
import net.aechronis.logger.utils.LogMetadata

object WorldEditListener {
    // Large pastes would otherwise write one row with NBT per block (84M for the spawn schematic).
    // ponytail: per-batch threshold (WorldEdit flushes up to 32,768 blocks per event), so the last
    // partial batch of a big edit can still be logged block by block; track whole edits if that matters.
    private const val MAX_LOGGED_CHANGES = 10_000

    fun init() {
        Logger.eventNode.addListener(WorldEditBlockChangesEvent::class.java) { event ->
            if (event.changes.size > MAX_LOGGED_CHANGES) {
                val positions = event.changes.map { it.position }
                println(
                    "[Logger] ${event.actorName} changed ${event.changes.size} blocks with WorldEdit in " +
                        "${event.instance.uuid} between ${positions.minOf { it.blockX() }},${positions.minOf { it.blockY() }}," +
                        "${positions.minOf { it.blockZ() }} and ${positions.maxOf { it.blockX() }}," +
                        "${positions.maxOf { it.blockY() }},${positions.maxOf { it.blockZ() }} (not logged per block)",
                )
                return@addListener
            }
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
                        action = if (newBlock.air()) BlockAction.BREAK else BlockAction.PLACE,
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
