/**
 * Scheduler for giving towns income
 */

package luna.nodes.tasks

import luna.nodes.Nodes
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule

object IncomeManager {

    private var task: Task? = null

    // run scheduler for giving income
    fun start(period: Long) {
        if (this.task !== null || !Nodes.config.incomeEnabled) {
            return
        }

        this.task = MinecraftServer.getSchedulerManager()
            .buildTask { Nodes.runIncome() }
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
