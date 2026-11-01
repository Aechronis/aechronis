package net.aechronis.guard.listeners

import net.aechronis.guard.Guard
import net.aechronis.guard.flags.FlagName
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityTeleportEvent
import net.minestom.server.instance.Instance
import net.minestom.server.timer.TaskSchedule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object TeleportListener {
    private class Correction(
        val position: Pos,
        val instance: Instance,
    )

    private val allowedCorrections = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingCorrections = ConcurrentHashMap<UUID, Correction>()

    fun handle(event: EntityTeleportEvent) {
        val player = event.entity as? Player ?: return
        if (allowedCorrections.remove(player.uuid)) return
        val instance = player.instance ?: return
        val position = event.newPosition
        val correction = Correction(player.position, instance)
        var denied = false
        Guard.check(
            player,
            position.blockX(),
            position.blockY(),
            position.blockZ(),
            FlagName.TELEPORT,
        ) {
            denied = true
            // Keep the source of the first denied teleport while it awaits correction.
            if (pendingCorrections.putIfAbsent(player.uuid, correction) != null) return@check
            try {
                ModuleScheduler
                    .buildTask {
                        if (!pendingCorrections.remove(player.uuid, correction)) return@buildTask
                        if (!player.isOnline || player.instance !== correction.instance) return@buildTask
                        allowedCorrections.add(player.uuid)
                        try {
                            player.teleport(correction.position)
                        } finally {
                            allowedCorrections.remove(player.uuid)
                        }
                    }.delay(TaskSchedule.tick(1))
                    .schedule()
            } catch (error: Throwable) {
                pendingCorrections.remove(player.uuid, correction)
                throw error
            }
        }
        // A later permitted teleport supersedes a pending correction.
        if (!denied) pendingCorrections.remove(player.uuid)
    }

    fun reset() {
        allowedCorrections.clear()
        pendingCorrections.clear()
    }
}
