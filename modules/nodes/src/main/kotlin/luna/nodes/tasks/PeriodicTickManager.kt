/**
 * Centralized handler for long periodic tasks:
 * - Income, backup, cooldowns
 */

package luna.nodes

import luna.nodes.utils.FileWriteTask
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.concurrent.CompletableFuture

public object PeriodicTickManager {

    private var task: Task? = null

    // previous tick time
    private var previousTime: Long = 0L

    // run scheduler for saving backups
    public fun start(period: Long) {
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
            if (currTime > Nodes.lastIncomeTime + Config.incomePeriod) {
                Nodes.lastIncomeTime = currTime

                if (Config.incomeEnabled) {
                    Nodes.runIncome()
                }

                // save current time
                CompletableFuture.runAsync { FileWriteTask(currTime.toString(), Config.pathLastIncomeTime, null).run() }
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

    public fun stop() {
        val task = this.task
        if (task === null) {
            return
        }

        task.cancel()
        this.task = null
    }
}
