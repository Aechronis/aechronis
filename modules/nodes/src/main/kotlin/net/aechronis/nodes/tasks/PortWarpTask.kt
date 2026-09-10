package net.aechronis.nodes.tasks

import net.aechronis.nodes.Message
import net.aechronis.nodes.objects.Port
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import kotlin.math.roundToInt

/**
 * Task for running warp
 */
class PortWarpTask(
    val player: Player,
    val initialPos: Pos,
    val destination: Port,
    val timeWarp: Long,
) {

    companion object {
        private val activeWarps = java.util.concurrent.ConcurrentHashMap<Player, PortWarpTask>()

        internal fun isWarping(player: Player): Boolean = activeWarps.containsKey(player)

        internal fun cancelAll() {
            activeWarps.values.forEach { it.task?.cancel() }
            activeWarps.clear()
        }
    }

    // remaining time counter
    private var time = timeWarp

    private var task: Task? = null
    private val initialVehicle = player.vehicle

    private var portPos = Pos(
        (destination.chunkX * 16 + 8).toDouble(),
        player.position.y,
        (destination.chunkZ * 16 + 8).toDouble(),
    )

    fun start(): Task {
        check(activeWarps.putIfAbsent(player, this) == null) { "Player is already warping" }
        val runnable = object : Runnable {
            override fun run() {
                if (player.position.blockX() != initialPos.blockX() || player.position.blockY() != initialPos.blockY() || player.position.blockZ() != initialPos.blockZ() || player.vehicle !== initialVehicle) {
                    Message.announcement(player, "${ChatColor.RED}Moved! Stopped warping...")
                    task?.cancel()
                    activeWarps.remove(player, this@PortWarpTask)
                    return
                }

                time -= 100

                if (time <= 0) {
                    task?.cancel()
                    activeWarps.remove(player, this@PortWarpTask)

                    val vehicle = initialVehicle
                    if (vehicle == null) {
                        player.teleport(portPos)
                        Message.announcement(player, "${ChatColor.GREEN}Warped to ${destination.name}")
                        return
                    }

                    // must remove players from boat before teleporting
                    val passengers = vehicle.passengers.toList()
                    passengers.forEach { passenger ->
                        vehicle.removePassenger(passenger)
                    }

                    vehicle.teleport(portPos)

                    passengers.forEach { passenger ->
                        passenger.teleport(portPos)
                    }

                    ModuleScheduler.scheduleNextTick {
                        passengers.forEach { passenger ->
                            vehicle.addPassenger(passenger)
                        }
                    }

                    Message.announcement(player, "${ChatColor.GREEN}Warped to ${destination.name}")
                } else {
                    val progress: Double = 1.0 - (time.toDouble() / timeWarp.toDouble())
                    Message.announcement(player, "Warping ${ChatColor.GREEN}${progressBar(progress)}")
                }
            }
        }

        try {
            this.task = ModuleScheduler
                .buildTask { runnable.run() }
                .delay(TaskSchedule.millis(100))
                .repeat(TaskSchedule.millis(100))
                .schedule()
        } catch (error: Throwable) {
            activeWarps.remove(player, this)
            throw error
        }

        return this.task!!
    }

    /**
     * Create progress bar string. Input should be double
     * in range [0.0, 1.0] marking progress.
     */
    internal fun progressBar(progress: Double): String = when ((progress * 10.0).roundToInt()) {
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
