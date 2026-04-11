package net.aechronis.combat.tasks

import net.aechronis.combat.Combat
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.TaskSchedule

object CooldownManager {
    // run scheduler for updating gun cooldowns
    fun start() {
        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                    Combat.playerCooldowns[player] = (Combat.playerCooldowns[player] ?: 0) + 50L
                }
            }.repeat(TaskSchedule.tick(1))
            .schedule()
    }
}
