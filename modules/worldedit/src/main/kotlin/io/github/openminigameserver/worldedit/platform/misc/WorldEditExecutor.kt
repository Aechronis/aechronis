package io.github.openminigameserver.worldedit.platform.misc

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object WorldEditExecutor {
    val executor: ExecutorService =
        Executors.newSingleThreadExecutor {
            Thread(it, "WorldEdit-Operation-Queue").apply { isDaemon = true }
        }

    fun shutdown() {
        executor.shutdown()
    }
}
