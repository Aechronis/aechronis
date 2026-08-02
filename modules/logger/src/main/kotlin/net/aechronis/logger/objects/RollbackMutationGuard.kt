package net.aechronis.logger.objects

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object RollbackMutationGuard {
    private val inventoryPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val entities = ConcurrentHashMap.newKeySet<UUID>()
    private val storages = ConcurrentHashMap.newKeySet<String>()

    fun inventorySuppressed(playerUuid: UUID): Boolean = playerUuid in inventoryPlayers

    fun <T> suppressInventory(
        playerUuid: UUID,
        block: () -> T,
    ): T {
        inventoryPlayers += playerUuid
        return try {
            block()
        } finally {
            inventoryPlayers -= playerUuid
        }
    }

    fun storageSuppressed(storageId: String): Boolean = storageId in storages

    fun <T> suppressStorage(
        storageId: String,
        block: () -> T,
    ): T {
        storages += storageId
        return try {
            block()
        } finally {
            storages -= storageId
        }
    }

    fun entitySuppressed(entityUuid: UUID): Boolean = entityUuid in entities

    fun beginEntity(entityUuid: UUID) {
        entities += entityUuid
    }

    fun endEntity(entityUuid: UUID) {
        entities -= entityUuid
    }

    fun <T> suppressEntity(
        entityUuid: UUID,
        block: () -> T,
    ): T {
        entities += entityUuid
        return try {
            block()
        } finally {
            entities -= entityUuid
        }
    }
}
