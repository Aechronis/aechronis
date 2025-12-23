package phonon.nodes

import net.kyori.adventure.text.Component
import net.minestom.server.adventure.audience.Audiences
import org.bukkit.ChatColor
import net.minestom.server.command.CommandSender
import net.minestom.server.entity.Player

/**
 * Helper functions for printing messages for players.
 */
public object Message {

    public val PREFIX = "[Nodes]"
    public val COL_MSG = ChatColor.AQUA
    public val COL_ERROR = ChatColor.RED

    /**
     * Print generic plugin message to command sender's chat (either console
     * or player).
     */
    public fun print(sender: CommandSender?, s: String) {
        if (sender === null) {
            System.out.println("$PREFIX Message called with null sender: $s")
            return
        }

        val msg = Component.text("${COL_MSG}$s")
        sender.sendMessage(msg)
    }

    /**
     * Print error message to a command sender's chat (either console or player).
     */
    public fun error(sender: CommandSender?, s: String) {
        if (sender === null) {
            System.out.println("$PREFIX Message called with null sender: $s")
            return
        }

        val msg = Component.text("${COL_ERROR}$s")
        sender.sendMessage(msg)
    }

    /**
     * Wrapper around Audiences.all().sendMessage to send plugin formatted messages
     * to all players.
     */
    public fun broadcast(s: String) {
        val msg = Component.text("${COL_MSG}$s")
        Audiences.all().sendMessage(msg)
    }

    /**
     * Wrapper around paper sendActionBar to message to player's action bar
     * above hotbar.
     */
    public fun announcement(player: Player, s: String) {
        player.sendActionBar(Component.text(s))
    }
}
