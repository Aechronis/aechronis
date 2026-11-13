package net.aechronis.server.tasks

import com.sun.management.HotSpotDiagnosticMXBean
import net.aechronis.server.Server
import net.minestom.server.event.EventListener
import net.minestom.server.event.server.ServerTickMonitorEvent
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Writes a thread dump to `thread-dumps/` when the server stops ticking. Players only see a hung
 * tick as "Too Many Packets" kicks and timeouts, and the server has no JDK tools to dump it by
 * hand, so the dump is the only record of what the tick was waiting on.
 */
object TickWatchdog {
    private val stallNanos = TimeUnit.SECONDS.toNanos(System.getProperty("aechronis.tickWatchdog.stallSeconds")?.toLong() ?: 5)

    // Bounds the disk a server that keeps stalling can fill with dumps.
    private val dumpIntervalNanos = TimeUnit.MINUTES.toNanos(5)
    private val directory = Path.of("thread-dumps").toAbsolutePath()

    @Volatile private var lastTick = System.nanoTime()
    private var stallReported = false
    private var lastDump = 0L
    private var executor: ScheduledExecutorService? = null
    private var listener: EventListener<ServerTickMonitorEvent>? = null

    fun start() {
        check(executor == null) { "TickWatchdog is already started" }
        lastTick = System.nanoTime()
        // Fires once each tick's threads have all finished, so a single stuck tick thread stops it.
        listener = EventListener.of(ServerTickMonitorEvent::class.java) { lastTick = System.nanoTime() }.also(Server.eventNode::addListener)
        // A platform thread, so it still runs when the tick threads or the virtual-thread carriers are stuck.
        executor =
            Executors
                .newSingleThreadScheduledExecutor(
                    Thread
                        .ofPlatform()
                        .daemon()
                        .name("tick-watchdog")
                        .factory(),
                ).also { it.scheduleWithFixedDelay(::check, 1, 1, TimeUnit.SECONDS) }
    }

    /** Stops watching before shutdown halts ticking on purpose. */
    fun shutdown() {
        executor?.shutdownNow()
        executor = null
        listener?.let(Server.eventNode::removeListener)
        listener = null
    }

    private fun check() {
        try {
            val now = System.nanoTime()
            val stalled = now - lastTick
            if (stalled < stallNanos) {
                if (stallReported) println("[TickWatchdog] Ticking resumed.")
                stallReported = false
                return
            }
            if (stallReported) return
            stallReported = true
            val stalledMillis = TimeUnit.NANOSECONDS.toMillis(stalled)
            if (lastDump != 0L && now - lastDump < dumpIntervalNanos) {
                System.err.println("[TickWatchdog] No tick for $stalledMillis ms; a thread dump was written less than 5 minutes ago.")
                return
            }
            lastDump = now
            Files.createDirectories(directory)
            val file = directory.resolve("${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.txt")
            ManagementFactory
                .getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
                .dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN)
            Files.writeString(file, lockOwners(), StandardOpenOption.APPEND)
            System.err.println("[TickWatchdog] No tick for $stalledMillis ms; thread dump written to $file")
        } catch (error: Throwable) {
            // An exception would cancel the scheduled check for the rest of the run.
            System.err.println("[TickWatchdog] Failed to check the tick: ${error.message}")
            error.printStackTrace()
        }
    }

    /** Which thread holds each lock a platform thread is blocked on; the plain dump leaves this out. */
    private fun lockOwners(): String =
        buildString {
            append("\nBlocked platform threads and the owners of their locks:\n")
            for (info in ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
                val lock = info.lockName ?: continue
                append("  \"${info.threadName}\" ${info.threadState} on $lock")
                if (info.lockOwnerName != null) append(", held by \"${info.lockOwnerName}\" #${info.lockOwnerId}")
                append('\n')
            }
        }
}
