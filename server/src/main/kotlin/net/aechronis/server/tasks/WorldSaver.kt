package net.aechronis.server.tasks

import net.aechronis.nodes.Nodes
import net.aechronis.server.Server
import net.aechronis.vanilla.Vanilla
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.TaskSchedule
import java.util.concurrent.CompletableFuture

object WorldSaver {
    private const val SAVE_INTERVAL_MINUTES = 5L

    fun start() {
        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                runCatching(::saveCheckpoint).onFailure { error ->
                    System.err.println("Failed to save server checkpoint: ${error.message}")
                    error.printStackTrace()
                }
            }.repeat(TaskSchedule.minutes(SAVE_INTERVAL_MINUTES))
            .schedule()
    }

    // runs on the tick thread so all persistence domains observe the same quiescent state
    internal fun saveCheckpoint() {
        Vanilla.saveCheckpoint()
        Nodes.saveWorld(checkIfNeedsSave = true, async = false)
        saveWorld().join()
    }

    fun saveWorld(): CompletableFuture<Void> =
        CompletableFuture.allOf(
            Server.instance.saveChunksToStorage(),
            Server.instance.saveInstance(),
        )
}
