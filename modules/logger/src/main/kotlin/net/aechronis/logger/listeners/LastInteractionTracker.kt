package net.aechronis.logger.listeners

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object LastInteractionTracker {
    data class Interaction(
        val x: Int,
        val y: Int,
        val z: Int,
        val blockKey: String,
        val atMillis: Long,
    )

    private val lastInteracted = ConcurrentHashMap<UUID, Interaction>()

    fun record(
        uuid: UUID,
        x: Int,
        y: Int,
        z: Int,
        blockKey: String,
    ) {
        lastInteracted[uuid] = Interaction(x, y, z, blockKey, System.currentTimeMillis())
    }

    fun get(uuid: UUID): Interaction? = lastInteracted[uuid]
}
