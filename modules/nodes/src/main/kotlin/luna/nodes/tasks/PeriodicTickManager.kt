/**
 * Centralized handler for long periodic tasks:
 * - Income, backup, cooldowns
 */

package luna.nodes.tasks

import luna.nodes.Nodes
import luna.nodes.utils.FileWriteTask
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.concurrent.CompletableFuture

object PeriodicTickManager {

    private var task: Task? = null

    // previous tick time
    private var previousTime: Long = 0L

    // run scheduler for saving backups
    fun start(period: Long) {
        if (this.task !== null) {
            return
        }

        // initialize previous time
        previousTime = System.currentTimeMillis()

        // scheduler for writing backups
        val runnable = Runnable {
            // update time tick
            val currTime = System.currentTimeMillis()
            val capturedPreviousTime = previousTime
            previousTime = currTime

            // =================================
            // income cycle
            // =================================
            if (currTime > Nodes.lastIncomeTime + Nodes.config.incomePeriod) {
                Nodes.lastIncomeTime = currTime

                if (Nodes.config.incomeEnabled) {
                    Nodes.runIncome()
                }

                // save current time
                CompletableFuture.runAsync { FileWriteTask(currTime.toString(), Nodes.config.pathLastIncomeTime, null).run() }
            }

            // =================================
            // town, resident cooldowns
            // =================================
            val currTime2 = System.currentTimeMillis()
            val dt = currTime2 - capturedPreviousTime
            Nodes.townMoveHomeCooldownTick(dt)
            Nodes.residentTownCreateCooldownTick(dt)
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
