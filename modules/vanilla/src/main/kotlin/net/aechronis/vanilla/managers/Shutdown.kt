package net.aechronis.vanilla.managers

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.TaskSchedule
import java.util.concurrent.atomic.AtomicLong

object Shutdown {
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 60L * SECONDS_PER_MINUTE

    private val scheduledShutdown = AtomicLong(0)

    /** Schedules a shutdown and returns false if one is already in progress. */
    fun schedule(seconds: Long): Boolean {
        require(seconds > 0)
        val deadline = System.currentTimeMillis() + seconds * 1000
        if (!scheduledShutdown.compareAndSet(0, deadline)) return false

        broadcast(Component.text("Server shutting down in ${formatTime(seconds)}.", NamedTextColor.YELLOW))
        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                val remaining = secondsUntil(deadline)
                if (remaining <= 0) {
                    broadcast(Component.text("Server shutting down now!", NamedTextColor.RED))
                    scheduledShutdown.set(0)
                    MinecraftServer.stopCleanly()
                    return@buildTask
                }

                announcementFor(remaining)?.let { message ->
                    broadcast(Component.text("Server shutting down in ${formatTime(message)}.", NamedTextColor.YELLOW))
                }
            }.delay(TaskSchedule.seconds(1))
            .repeat(TaskSchedule.seconds(1))
            .schedule()
        return true
    }

    internal fun announcementFor(remaining: Long): Long? =
        when {
            remaining > 60 && remaining % (5 * SECONDS_PER_MINUTE) == 0L -> remaining
            remaining == 60L || remaining == 30L -> remaining
            remaining in 11..29 && remaining % 5 == 0L -> remaining
            remaining in 1..10 -> remaining
            else -> null
        }

    internal fun secondsUntil(
        deadline: Long,
        now: Long = System.currentTimeMillis(),
    ): Long = ((deadline - now).coerceAtLeast(0) + 999) / 1000

    private fun formatTime(seconds: Long): String =
        when {
            seconds >= SECONDS_PER_HOUR -> {
                val hours = seconds / SECONDS_PER_HOUR
                val minutes = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
                "${hours}h ${minutes}m"
            }
            seconds >= SECONDS_PER_MINUTE -> "${seconds / SECONDS_PER_MINUTE}m ${seconds % SECONDS_PER_MINUTE}s"
            else -> "$seconds second${if (seconds == 1L) "" else "s"}"
        }

    private fun broadcast(message: Component) {
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { it.sendMessage(message) }
    }
}
