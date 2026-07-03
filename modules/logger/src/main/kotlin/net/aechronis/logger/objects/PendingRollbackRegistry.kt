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

    fun register(
        playerUuid: UUID,
        plan: RollbackPlan,
    ): String {
        sweep()
        val token = UUID.randomUUID().toString().take(8)
        pending[token] = Pending(playerUuid, plan, System.currentTimeMillis())
        return token
    }

    fun consume(
        playerUuid: UUID,
        token: String,
    ): RollbackPlan? {
        sweep()
        val entry = pending[token] ?: return null
        if (entry.playerUuid != playerUuid) return null
        pending.remove(token)
        return entry.plan
    }

    fun cancel(
        playerUuid: UUID,
        token: String,
    ): Boolean {
        val entry = pending[token] ?: return false
        if (entry.playerUuid != playerUuid) return false
        pending.remove(token)
        return true
    }

    private fun sweep() {
        val cutoff = System.currentTimeMillis() - TTL_MILLIS
        pending.entries.removeIf { it.value.createdAtMillis < cutoff }
    }
}
