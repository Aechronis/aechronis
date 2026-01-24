package luna.nodes.tasks

import luna.nodes.Message
import luna.nodes.Nodes
import luna.nodes.objects.Port
import luna.nodes.utils.ChatColor
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import kotlin.math.roundToInt

/**
 * Task for running warp
 */
class PortWarpTask(
    val player: Player,
    val vehicle: Entity,
    val initialPos: Pos,
    val destination: Port,
    val timeWarp: Long,
) {

    // remaining time counter
    private var time = timeWarp

    private var task: Task? = null

    private var portPos = Pos(destination.locX.toDouble(), vehicle.position.y, destination.locZ.toDouble())

    fun start(): Task {
        val runnable = object : Runnable {
            override fun run() {
                if (player.position.x.toInt() != initialPos.blockX() || player.position.y.toInt() != initialPos.blockY() || player.position.z.toInt() != initialPos.blockZ() || player.vehicle == null) {
                    Message.announcement(player, "${ChatColor.RED}Moved! Stopped warping...")
                    task?.cancel()
                    Nodes.playerWarpTasks.remove(player)
                    return
                }

                time -= 100

                if (time <= 0) {
                    task?.cancel()
                    Nodes.playerWarpTasks.remove(player)

                    val vehicle = player.vehicle!!
                    val passengers = vehicle.passengers.toList()

                    // must remove players from boat before teleporting
                    passengers.forEach { passenger ->
                        vehicle.removePassenger(passenger)
                    }

                    vehicle.teleport(portPos)

                    passengers.forEach { passenger ->
                        passenger.teleport(portPos)
                    }

                    MinecraftServer.getSchedulerManager().scheduleNextTick {
                        passengers.forEach { passenger ->
                            vehicle.addPassenger(passenger)
                        }
                    }

                    Message.announcement(player, "${ChatColor.GREEN}Warped to ${destination.name}")
                } else {
                    val progress : Double = 1.0 - (time.toDouble() / timeWarp.toDouble())
                    Message.announcement(player, "Warping ${ChatColor.GREEN}${progressBar(progress)}")
                }
            }
        }

        this.task = MinecraftServer.getSchedulerManager()
            .buildTask { runnable.run() }
            .delay(TaskSchedule.millis(100))
            .repeat(TaskSchedule.millis(100))
            .schedule()

        return this.task!!
    }

    /**
     * Create progress bar string. Input should be double
     * in range [0.0, 1.0] marking progress.
     */
    internal fun progressBar(progress: Double): String = when ((progress * 10.0).roundToInt().toInt()) {
        0 -> "\u2503\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2503"
        1 -> "\u2503\u2588\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2503"
        2 -> "\u2503\u2588\u2588\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2503"
        3 -> "\u2503\u2588\u2588\u2588\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2503"
        4 -> "\u2503\u2588\u2588\u2588\u2588\u2592\u2592\u2592\u2592\u2592\u2592\u2503"
        5 -> "\u2503\u2588\u2588\u2588\u2588\u2588\u2592\u2592\u2592\u2592\u2592\u2503"
        6 -> "\u2503\u2588\u2588\u2588\u2588\u2588\u2588\u2592\u2592\u2592\u2592\u2503"
        7 -> "\u2503\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2592\u2592\u2592\u2503"
        8 -> "\u2503\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2592\u2592\u2503"
        9 -> "\u2503\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2592\u2503"
        10 -> "\u2503\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2503"
        else -> ""
    }
}


