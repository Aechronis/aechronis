package net.aechronis.logger.utils

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

object Pages {
    const val PER_PAGE = 10

    private data class Session(
        val title: String,
        val lines: List<Component>,
    )

    private val sessions = ConcurrentHashMap<UUID, Session>()

    fun send(
        player: Player,
        title: String,
        lines: List<Component>,
    ) {
        sessions[player.uuid] = Session(title, lines)
        showPage(player, 1)
    }

    fun showPage(
        player: Player,
        page: Int,
    ) {
        val session = sessions[player.uuid]
        if (session == null) {
            player.sendMessage(Component.text("[Logger] no active results to page through", NamedTextColor.GRAY))
            return
        }

        val total = session.lines.size
        val totalPages = maxOf(1, ceil(total / PER_PAGE.toDouble()).toInt())
        val current = page.coerceIn(1, totalPages)
        val from = (current - 1) * PER_PAGE
        val to = minOf(from + PER_PAGE, total)

        player.sendMessage(header(session.title, current, totalPages, total))
        for (i in from until to) {
            player.sendMessage(session.lines[i])
        }
        if (totalPages > 1) {
            player.sendMessage(footer(current, totalPages))
        }
    }

    private fun header(
        title: String,
        page: Int,
        totalPages: Int,
        total: Int,
    ): Component =
        Component
            .text("[Logger] ", NamedTextColor.GOLD)
            .append(Component.text(title, NamedTextColor.YELLOW))
            .append(Component.text(" (page $page/$totalPages, $total total)", NamedTextColor.GRAY))

    private fun footer(
        page: Int,
        totalPages: Int,
    ): Component {
        val prev =
            if (page > 1) {
                Component
                    .text("[<< Prev]", NamedTextColor.AQUA)
                    .hoverEvent(HoverEvent.showText(Component.text("Page ${page - 1}")))
                    .clickEvent(ClickEvent.runCommand("/logger page ${page - 1}"))
            } else {
                Component.text("[<< Prev]", NamedTextColor.DARK_GRAY)
            }
        val next =
            if (page < totalPages) {
                Component
                    .text("[Next >>]", NamedTextColor.AQUA)
                    .hoverEvent(HoverEvent.showText(Component.text("Page ${page + 1}")))
                    .clickEvent(ClickEvent.runCommand("/logger page ${page + 1}"))
            } else {
                Component.text("[Next >>]", NamedTextColor.DARK_GRAY)
            }
        return prev
            .append(Component.text("  ", NamedTextColor.GRAY))
            .append(next)
    }
}
