package net.aechronis.logger.listeners

import net.aechronis.combat.events.ExplosionBlockChangeType
import net.aechronis.combat.events.ExplosionBlockDamageEvent
import net.aechronis.logger.Logger
import net.aechronis.logger.objects.BlockAction
import net.aechronis.logger.objects.BlockLogEntry
import net.aechronis.logger.utils.ItemCodec
import net.aechronis.logger.utils.LogMetadata
import java.util.UUID

object CombatExplosionListener {
    private val combatActorUuid = UUID(0L, 0L)

    private fun onExplosion(event: ExplosionBlockDamageEvent) {
        if (event.changes.isEmpty()) return

        val timestamp = System.currentTimeMillis()
        val playerUuid = event.sourceUuid ?: combatActorUuid
        val playerName = event.sourceName ?: LogMetadata.COMBAT
        val entries =
            event.changes.map { change ->
                BlockLogEntry(
                    timestamp = timestamp,
                    playerUuid = playerUuid,
                    playerName = playerName,
                    x = change.position.blockX(),
                    y = change.position.blockY(),
                    z = change.position.blockZ(),
                    blockOld = change.oldBlock.key().asString(),
                    blockNew = change.newBlock.key().asString(),
                    action =
                        when (change.type) {
                            ExplosionBlockChangeType.DAMAGE -> BlockAction.BREAK
                            ExplosionBlockChangeType.FIRE -> BlockAction.PLACE
                        },
                    instanceUuid = event.instance.uuid,
                    blockOldState = change.oldBlock.state(),
                    blockNewState = change.newBlock.state(),
                    blockOldNbt = ItemCodec.encodeBlockNbt(change.oldBlock.nbt()),
                    blockNewNbt = ItemCodec.encodeBlockNbt(change.newBlock.nbt()),
                    source = LogMetadata.COMBAT,
                    origin = LogMetadata.COMBAT,
                )
            }

        Logger.repository.insertAllAsync(entries).exceptionally { exception ->
            println("[Logger] failed to record combat explosion block changes: $exception")
            null
        }
    }

    fun init() {
        Logger.eventNode.addListener(ExplosionBlockDamageEvent::class.java, ::onExplosion)
    }
}
