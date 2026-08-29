package net.aechronis.server.tasks

import net.aechronis.server.Server
import net.aechronis.server.modules.ModuleEvents
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.adventure.audience.Audiences
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.server.ServerTickMonitorEvent
import net.minestom.server.monitoring.TickMonitor
import net.minestom.server.timer.TaskSchedule
import net.minestom.server.utils.MathUtils
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object TabManager {
    private const val BYTES_PER_MEBIBYTE = 1024L * 1024L
    private const val NANOSECONDS_PER_SECOND = 1_000_000_000L
    private const val UPDATE_INTERVAL_TICKS = 20

    private val runtime = Runtime.getRuntime()
    private val maxMemory = runtime.maxMemory() / BYTES_PER_MEBIBYTE
    private val header =
        Component
            .newline()
            .append(Component.text("\ue002").appendNewline()) // server logo
            .appendNewline()
            .appendNewline()
            .appendNewline()
            .appendNewline()
            .appendNewline()
            .append(Component.text("A New Millennium", NamedTextColor.GRAY))
            .appendNewline()
            .append(Component.text("                                      ")) // force tab width
    private var eventNode: EventNode<Event>? = null

    fun start() {
        check(eventNode == null) { "TabManager is already started" }
        val lastTick = AtomicReference<TickMonitor>()
        val ticksInPastSecond = AtomicInteger()
        val recentTicks = ArrayDeque<Long>()

        val node = EventNode.all("a-new-millenium-tab")
        node.addListener(ServerTickMonitorEvent::class.java) { event ->
            lastTick.set(event.tickMonitor)

            val now = System.nanoTime()
            val cutoff = now - NANOSECONDS_PER_SECOND
            recentTicks.addLast(now)
            while (recentTicks.peekFirst()?.let { it <= cutoff } == true) {
                recentTicks.removeFirst()
            }
            ticksInPastSecond.set(recentTicks.size)
        }
        ModuleEvents.addChild(Server.eventNode, node)
        eventNode = node

        ModuleScheduler
            .buildTask {
                val tickTime = lastTick.get()?.tickTime ?: 0.0
                val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MEBIBYTE
                val footer = createFooter(tickTime, ticksInPastSecond.get(), usedMemory)

                Audiences.players().sendPlayerListHeaderAndFooter(header, footer)
            }.repeat(TaskSchedule.tick(UPDATE_INTERVAL_TICKS))
            .schedule()
    }

    fun shutdown() {
        eventNode?.let(Server.eventNode::removeChild)
        eventNode = null
        Audiences.players().sendPlayerListHeaderAndFooter(Component.empty(), Component.empty())
    }

    private fun createFooter(
        tickTime: Double,
        ticksPerSecond: Int,
        usedMemory: Long,
    ): Component =
        Component
            .newline()
            .append(Component.text("TPS: ", NamedTextColor.GOLD))
            .append(
                Component.text(
                    "$ticksPerSecond / 20",
                    NamedTextColor.GRAY,
                ),
            ).appendNewline()
            .append(Component.text("MSPT: ", NamedTextColor.GOLD))
            .append(Component.text("${MathUtils.round(tickTime, 1)} ms / 50ms", NamedTextColor.GRAY))
            .appendNewline()
            .append(Component.text("Memory: ", NamedTextColor.GOLD))
            .append(Component.text("$usedMemory MB / $maxMemory MB", NamedTextColor.GRAY))
            .appendNewline()
}
