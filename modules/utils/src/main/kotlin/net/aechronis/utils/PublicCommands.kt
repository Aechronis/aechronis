package net.aechronis.utils

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Remembers which players just ran a command executor that needs no permission, so audits can skip basic commands. */
object PublicCommands {
    private val ran = ConcurrentHashMap.newKeySet<UUID>()

    fun mark(player: UUID) {
        ran.add(player)
    }

    /** True if [player] ran a public executor since the last call. */
    fun consume(player: UUID): Boolean = ran.remove(player)
}
