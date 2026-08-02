/**
 * Scheduler for giving towns income
 */

package net.aechronis.nodes.tasks

import net.aechronis.nodes.Nodes
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

object IncomeManager {

    private var task: Task? = null

    fun start() {
        if (this.task !== null || !Nodes.config.incomeEnabled) {
            return
        }
        scheduleNext()
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    // schedule at the next hour
    private fun scheduleNext() {
        this.task = MinecraftServer.getSchedulerManager()
            .buildTask {
                Nodes.runIncome()
                scheduleNext()
            }
            .delay(TaskSchedule.millis(millisUntilNextHour()))
            .schedule()
    }

    private fun millisUntilNextHour(): Long {
        val now = ZonedDateTime.now()
        val nextHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
        return Duration.between(now, nextHour).toMillis()
    }
}
