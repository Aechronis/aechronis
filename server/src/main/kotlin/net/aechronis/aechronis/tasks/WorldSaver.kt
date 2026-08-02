package net.aechronis.aechronis.tasks

import net.aechronis.aechronis.Aechronis
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.TaskSchedule

object WorldSaver {
    private const val SAVE_INTERVAL_MINUTES = 5L

    fun start() {
        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                Aechronis.instance.saveChunksToStorage()
            }.repeat(TaskSchedule.minutes(SAVE_INTERVAL_MINUTES))
            .schedule()
    }
}
