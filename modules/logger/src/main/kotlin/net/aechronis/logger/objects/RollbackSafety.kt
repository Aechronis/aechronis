package net.aechronis.logger.objects

import net.aechronis.logger.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object RollbackSafety {
    private val overrides = ConcurrentHashMap<UUID, Boolean>()

    fun enabled(playerUuid: UUID): Boolean = overrides[playerUuid] ?: Logger.config.rollbackSafetyDefault

    fun set(
        playerUuid: UUID,
        enabled: Boolean,
    ) {
        overrides[playerUuid] = enabled
    }

    fun toggle(playerUuid: UUID): Boolean {
        val enabled = !enabled(playerUuid)
        set(playerUuid, enabled)
        return enabled
    }

    fun clear(playerUuid: UUID) {
        overrides.remove(playerUuid)
    }
}
