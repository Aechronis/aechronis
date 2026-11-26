package net.aechronis.server.tasks

import net.aechronis.server.Server
import net.aechronis.server.modules.ModuleEvents
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
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

    private const val LOGO_GAP_SPACES = 10
    private val DISCORD = TextColor.color(0x5865F2)

    private var eventNode: EventNode<Event>? = null

    fun start() {
        check(eventNode == null) { "TabManager is already started" }
        val lastTick = AtomicReference<TickMonitor>()
        val ticksInPastSecond = AtomicInteger()
        val recentTicks = ArrayDeque<Long>()

        val node = EventNode.all("victorian-era-tab")
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
                Audiences.players().sendPlayerListHeaderAndFooter(createHeader(tickTime, ticksInPastSecond.get()), Component.empty())
            }.repeat(TaskSchedule.tick(UPDATE_INTERVAL_TICKS))
            .schedule()
    }

    fun shutdown() {
        eventNode?.let(Server.eventNode::removeChild)
        eventNode = null
        Audiences.players().sendPlayerListHeaderAndFooter(Component.empty(), Component.empty())
    }

    /** Small logo on top; TPS/MSPT to its left and the links to its right, on the two lines beside it. */
    private fun createHeader(
        tickTime: Double,
        ticksPerSecond: Int,
    ): Component =
        Component
            .newline()
            .append(Component.text("")) // server logo, 32px tall (font/default.json)
            .appendNewline()
            .append(
                row(
                    stat("TPS: ", "$ticksPerSecond / 20"),
                    "TPS: $ticksPerSecond / 20",
                    Component.text("discord.gg/aechronis", DISCORD),
                    "discord.gg/aechronis",
                ),
            ).appendNewline()
            .append(
                row(
                    stat("MSPT: ", "${MathUtils.round(tickTime, 1)} ms / 50ms"),
                    "MSPT: ${MathUtils.round(tickTime, 1)} ms / 50ms",
                    Component.text("aechronis.net", NamedTextColor.GOLD),
                    "aechronis.net",
                ),
            ).appendNewline()
            .appendNewline()
            .append(Component.text("Victorian Era", NamedTextColor.GRAY))
            .appendNewline()

    private fun stat(
        label: String,
        value: String,
    ) = Component.text(label, NamedTextColor.GOLD).append(Component.text(value, NamedTextColor.GRAY))

    /**
     * Tab header lines are centered, so the logo (centered on its own line, drawn over the lines below) sits in the
     * middle of this row only if both sides are equally wide: pad the narrower side with spaces on its outer edge.
     */
    private fun row(
        left: Component,
        leftText: String,
        right: Component,
        rightText: String,
    ): Component {
        val leftWidth = textWidth(leftText)
        val rightWidth = textWidth(rightText)
        val widest = maxOf(leftWidth, rightWidth)

        fun pad(width: Int) = Component.text(" ".repeat((widest - width) / SPACE_WIDTH))
        return pad(leftWidth)
            .append(left)
            .append(Component.text(" ".repeat(LOGO_GAP_SPACES)))
            .append(right)
            .append(pad(rightWidth))
    }

    private const val SPACE_WIDTH = 4

    /** Advance width in pixels of the default font (ASCII only, close enough to center a row). */
    private fun textWidth(text: String): Int =
        text.sumOf {
            when (it) {
                '!', '.', ',', ':', ';', 'i', '|' -> 2
                '\'', 'l', '`' -> 3
                ' ', 'I', 't', '[', ']' -> 4
                '"', '(', ')', '*', '<', '>', 'f', 'k', '{', '}' -> 5
                '@', '~' -> 7
                else -> 6
            }.toInt()
        }
}
