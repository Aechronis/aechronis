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
import net.aechronis.nodes.objects.Nation.NationSaveState
import net.aechronis.nodes.objects.Resident.ResidentSaveState
import net.aechronis.nodes.objects.Town.TownSaveState
import net.aechronis.nodes.serdes.Serializer
import net.aechronis.nodes.utils.saveStringToFile
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Runnable task to save world. This can be run either synchronously or
 * asynchronously by the caller.
 *
 */
class TaskSaveWorld(
    val residentsSnapshot: List<ResidentSaveState>,
    val townsSnapshot: List<TownSaveState>,
    val nationsSnapshot: List<NationSaveState>,
    val backupTimestamp: Long?,
) : Runnable {
    override fun run() {
        // serialize world state
        val jsonStr = Serializer.worldToJson(
            residentsSnapshot,
            townsSnapshot,
            nationsSnapshot,
        )

        saveStringToFile(jsonStr, Nodes.config.pathTowns)

        // if backup timestamp millis timestamp (using System.currentTimeMillis())
        // was provided, copy this saved world state to backup folder
        if (backupTimestamp != null) {
            TaskSaveBackup(backupTimestamp).run()
        }
    }
}

// backup format
private val BACKUP_DATE_FORMATTER = SimpleDateFormat("yyyy.MM.dd.HH.mm.ss")

/**
 * Save timestamped backup file of towns.json into backup folder.
 */
internal class TaskSaveBackup(
    val timestamp: Long, // millis timestamp from System.currentTimeMillis()
) : Runnable {
    override fun run() {
        if (Files.exists(Nodes.config.pathTowns)) {
            Files.createDirectories(Nodes.config.pathBackup) // create backup folder if it does not exist

            // save towns file backup
            val date = Date(timestamp)
            val backupName = "towns.${BACKUP_DATE_FORMATTER.format(date)}.json"
            val pathBackup = Nodes.config.pathBackup.resolve(backupName)
            Files.copy(Nodes.config.pathTowns, pathBackup)
        }

        // save last backup timestamp to file
        saveStringToFile(timestamp.toString(), Nodes.config.pathLastBackupTime)
    }
}

class TaskSaveBuildings(
    val buildingsSnapshot: List<BuildingSaveState>,
    val pathBuildingsSave: Path,
) : Runnable {
    override fun run() {
        val jsonStr = Serializer.buildingsToJson(buildingsSnapshot)
        saveStringToFile(jsonStr, pathBuildingsSave)
    }
}

/**
 * Async periodic tick scheduler to signal main thread
 * to save world state.
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

        this.task = MinecraftServer.getSchedulerManager()
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
