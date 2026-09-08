/**
 * Scheduler for saving Nodes world state to towns.json
 *
 * Runs world save to towns.json on a fixed tick schedule.
 * If we save everytime world state updates, players can lag servers
 * by spamming commands. Running on fixed schedules avoids
 * this exploit.
 *
 */

package net.aechronis.nodes.tasks

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.BuildingSaveState
import net.aechronis.nodes.serdes.Serializer
import net.aechronis.nodes.serdes.WorldSaveState
import net.aechronis.nodes.serdes.snapshotList
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Runnable task to save world. This can be run either synchronously or
 * asynchronously by the caller.
 *
 */
internal class TaskSaveWorld(
    private val snapshot: WorldSaveState,
    private val pathTowns: Path,
    private val backupTask: TaskSaveBackup?,
) : Runnable {
    override fun run() {
        AtomicFiles.writeString(pathTowns, snapshot.toJsonString())
        backupTask?.run()
    }
}

// backup format
private val BACKUP_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd.HH.mm.ss").withZone(ZoneId.systemDefault())

/**
 * Save timestamped backup file of towns.json into backup folder.
 */
internal class TaskSaveBackup(
    private val timestamp: Long, // millis timestamp from System.currentTimeMillis()
    private val pathTowns: Path,
    private val pathBackup: Path,
    private val pathLastBackupTime: Path,
) : Runnable {
    override fun run() {
        if (Files.exists(pathTowns)) {
            // save towns file backup
            val backupName = "towns.${BACKUP_DATE_FORMATTER.format(Instant.ofEpochMilli(timestamp))}.json"
            AtomicFiles.copy(pathTowns, pathBackup.resolve(backupName))
        }

        // save last backup timestamp to file
        AtomicFiles.writeString(pathLastBackupTime, timestamp.toString())
    }
}

class TaskSaveBuildings(
    buildingsSnapshot: List<BuildingSaveState>,
    private val pathBuildingsSave: Path,
) : Runnable {
    private val buildingsSnapshot = buildingsSnapshot.snapshotList()

    override fun run() {
        val jsonStr = Serializer.buildingsToJson(buildingsSnapshot)
        AtomicFiles.writeString(pathBuildingsSave, jsonStr)
    }
}

/**
 * Captures world state on the tick scheduler, then queues serialization and file writes.
 */
object SaveManager {

    private var task: Task? = null

    fun start(period: Long) {
        if (this.task !== null || !Nodes.config.save) {
            return
        }

        // create save folder if it does not exist
        Files.createDirectories(Paths.get(Nodes.config.path).normalize())

        // scheduler for saving world
        val runnable = Runnable {
            Nodes.saveWorld(
                checkIfNeedsSave = true,
                async = true,
            )
        }

        this.task = ModuleScheduler
            .buildTask(runnable)
            .delay(TaskSchedule.millis(period))
            .repeat(TaskSchedule.millis(period))
            .schedule()
    }

    fun stop() {
        val task = this.task
        if (task === null) {
            return
        }

        task.cancel()
        this.task = null
    }
}
