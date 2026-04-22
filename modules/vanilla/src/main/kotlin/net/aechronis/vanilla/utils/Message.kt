package net.aechronis.vanilla.utils

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.minestom.server.command.CommandSender
import net.minestom.server.entity.Player
import kotlin.math.roundToInt

/**
 * Helper functions for printing messages for players.
 */
object Message {
    const val PREFIX = "[Vanilla]"

    /**
     * Print generic message to command sender's chat (either console
     * or player).
     */
    fun print(
        sender: CommandSender?,
        s: String,
    ) {
        if (sender === null) {
            println("$PREFIX Message called with null sender: $s")
            return
        }

        val msg = Component.text(s, NamedTextColor.DARK_GREEN)
        sender.sendMessage(msg)
    }

    /**
     * Print error message to a command sender's chat (either console or player).
     */
    fun error(
        sender: CommandSender?,
        s: String,
    ) {
        if (sender === null) {
            println("$PREFIX Message called with null sender: $s")
            return
        }

        val msg = Component.text(s, NamedTextColor.RED)
        sender.sendMessage(msg)
    }

    /**
     * Wrapper around paper sendActionBar to message to player's action bar
     * above hotbar.
     */
    fun announcement(
        player: Player,
        s: String,
    ) {
        player.sendActionBar(Component.text(s))
    }

    /**
     * Create progress bar string. Input should be double
     * in range [0.0, 1.0] marking progress.
     */
    fun progressBar(
        progress: Double,
        color1: TextColor = NamedTextColor.GRAY,
        color2: TextColor = NamedTextColor.DARK_GRAY,
    ): Component =
        when (
            (
                progress *
                    10.0
            ).roundToInt()
        ) {
            0 -> Component.text("..........").color(color2)
            1 -> Component.text(".").color(color1).append(Component.text(".........", color2))
            2 -> Component.text("..").color(color1).append(Component.text("........", color2))
            3 -> Component.text("...").color(color1).append(Component.text(".......", color2))
            4 -> Component.text("....").color(color1).append(Component.text("......", color2))
            5 -> Component.text(".....").color(color1).append(Component.text(".....", color2))
            6 -> Component.text("......").color(color1).append(Component.text("....", color2))
            7 -> Component.text(".......").color(color1).append(Component.text("...", color2))
            8 -> Component.text("........").color(color1).append(Component.text("..", color2))
            9 -> Component.text(".........").color(color1).append(Component.text(".", color2))
            10 -> Component.text("..........").color(color1)
            else -> Component.empty()
        }
}
