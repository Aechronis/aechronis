package net.aechronis.logger.listeners

import net.aechronis.logger.Logger
import net.aechronis.logger.objects.EntityChange
import net.aechronis.logger.objects.EntityChangeAction
import net.aechronis.logger.objects.RollbackMutationGuard
import net.aechronis.logger.utils.EntityStateCodec
import net.minestom.server.entity.ItemEntity
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityDespawnEvent
import net.minestom.server.event.entity.EntitySpawnEvent

object EntityChangeListener {
    fun init() {
        Logger.eventNode.addListener(EntitySpawnEvent::class.java) { event ->
            val entity = event.entity
            if (
                entity is Player ||
                entity is ItemEntity ||
                RollbackMutationGuard.entitySuppressed(entity.uuid)
            ) {
                return@addListener
            }
            record(entity, event.spawnInstance.uuid, EntityChangeAction.SPAWN)
        }
        Logger.eventNode.addListener(EntityDespawnEvent::class.java) { event ->
            val entity = event.entity
            val instance = entity.instance ?: return@addListener
            if (
                entity is Player ||
                entity is ItemEntity ||
                RollbackMutationGuard.entitySuppressed(entity.uuid)
            ) {
                return@addListener
            }
            record(entity, instance.uuid, EntityChangeAction.DESPAWN)
        }
    }

    private fun record(
        entity: net.minestom.server.entity.Entity,
        instanceUuid: java.util.UUID,
        action: EntityChangeAction,
    ) {
        Logger.entityChange
            .insertAsync(
                EntityChange(
                    timestamp = System.currentTimeMillis(),
                    entityUuid = entity.uuid,
                    entityType = entity.entityType.key().asString(),
                    action = action,
                    instanceUuid = instanceUuid,
                    position = entity.position,
                    velocity = entity.velocity,
                    tagData = EntityStateCodec.encode(entity),
                ),
            ).exceptionally { exception ->
                println("[Logger] failed to record entity change: $exception")
                null
            }
    }
}
