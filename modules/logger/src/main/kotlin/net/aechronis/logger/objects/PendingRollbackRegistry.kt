package net.aechronis.logger.objects

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PendingRollbackRegistry {
    private const val TTL_MILLIS = 120_000L

    private data class Pending(
        val playerUuid: UUID,
        val plan: RollbackPlan,
        val createdAtMillis: Long,
    )

    private val pending = ConcurrentHashMap<String, Pending>()
    private val tokenByPlayer = ConcurrentHashMap<UUID, String>()

    @Synchronized
    fun register(
        playerUuid: UUID,
        plan: RollbackPlan,
    ): String {
        sweep()
        clearPlayer(playerUuid)
        var token: String
        do {
            token =
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
        } while (pending.containsKey(token))
        pending[token] = Pending(playerUuid, plan, System.currentTimeMillis())
        tokenByPlayer[playerUuid] = token
        return token
    }

    @Synchronized
    fun consume(
        playerUuid: UUID,
        token: String,
    ): RollbackPlan? {
        sweep()
        val entry = pending[token] ?: return null
        if (entry.playerUuid != playerUuid) return null
        if (!pending.remove(token, entry)) return null
        tokenByPlayer.remove(playerUuid, token)
        return entry.plan
    }

    @Synchronized
    fun cancel(
        playerUuid: UUID,
        token: String,
    ): Boolean {
        val entry = pending[token] ?: return false
        if (entry.playerUuid != playerUuid) return false
        val removed = pending.remove(token, entry)
        if (removed) tokenByPlayer.remove(playerUuid, token)
        return removed
    }

    @Synchronized
    fun clearPlayer(playerUuid: UUID) {
        val token = tokenByPlayer.remove(playerUuid) ?: return
        pending.remove(token)
    }

    @Synchronized
    fun clear() {
        pending.clear()
        tokenByPlayer.clear()
    }

    private fun sweep() {
        val cutoff = System.currentTimeMillis() - TTL_MILLIS
        pending.entries.removeIf { (token, value) ->
            val expired = value.createdAtMillis < cutoff
            if (expired) tokenByPlayer.remove(value.playerUuid, token)
            expired
        }
    }
}
