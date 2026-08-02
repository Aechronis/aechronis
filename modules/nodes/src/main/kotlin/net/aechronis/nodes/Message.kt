package net.aechronis.nodes

import net.aechronis.nodes.utils.ChatColor
import net.kyori.adventure.text.Component
import net.minestom.server.adventure.audience.Audiences
import net.minestom.server.command.CommandSender
import net.minestom.server.entity.Player

/**
 * Helper functions for printing messages for players.
 */
object Message {

    const val PREFIX = "[Nodes]"
    val COL_MSG = ChatColor.AQUA
    val COL_ERROR = ChatColor.RED

    /**
     * Print generic message to command sender's chat (either console
     * or player).
     */
    fun print(sender: CommandSender?, s: String) {
        if (sender === null) {
            println("$PREFIX Message called with null sender: $s")
            return
        }

        val msg = Component.text("${COL_MSG}$s")
        sender.sendMessage(msg)
    }

    /**
     * Print error message to a command sender's chat (either console or player).
     */
    fun error(sender: CommandSender?, s: String) {
        if (sender === null) {
            println("$PREFIX Message called with null sender: $s")
            return
        }

        val msg = Component.text("${COL_ERROR}$s")
        sender.sendMessage(msg)
    }

    /**
     * Wrapper around Audiences.all().sendMessage to send formatted messages
     * to all players.
     */
    fun broadcast(s: String) {
        val msg = Component.text("${COL_MSG}$s")
        Audiences.all().sendMessage(msg)
    }

    /**
     * Wrapper around paper sendActionBar to message to player's action bar
     * above hotbar.
     */
    fun announcement(player: Player, s: String) {
        player.sendActionBar(Component.text(s))
    }
}
