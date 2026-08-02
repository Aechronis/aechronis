package net.aechronis.server.tasks

import net.aechronis.server.Server
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.TaskSchedule

object WorldSaver {
    private const val SAVE_INTERVAL_MINUTES = 5L

    fun start() {
        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                Server.instance.saveChunksToStorage()
            }.repeat(TaskSchedule.minutes(SAVE_INTERVAL_MINUTES))
            .schedule()
    }
}
