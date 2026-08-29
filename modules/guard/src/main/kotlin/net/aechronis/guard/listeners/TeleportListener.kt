package net.aechronis.guard.listeners

import net.aechronis.guard.Guard
import net.aechronis.guard.flags.FlagName
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityTeleportEvent
import net.minestom.server.timer.TaskSchedule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object TeleportListener {
    private val allowedCorrections = ConcurrentHashMap.newKeySet<UUID>()

    fun handle(event: EntityTeleportEvent) {
        val player = event.entity as? Player ?: return
        if (!allowedCorrections.remove(player.uuid)) {
            val position = event.newPosition
            Guard.check(
                player,
                position.blockX(),
                position.blockY(),
                position.blockZ(),
                FlagName.TELEPORT,
            ) {
                allowedCorrections.add(player.uuid)
                ModuleScheduler
                    .buildTask { player.teleport(player.position) }
                    .delay(TaskSchedule.tick(1))
                    .schedule()
            }
        }
    }

    fun reset() = allowedCorrections.clear()
}
