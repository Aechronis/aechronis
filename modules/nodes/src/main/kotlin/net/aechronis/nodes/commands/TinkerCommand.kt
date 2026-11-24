/** /tinker turns tinker mode on or off. */

package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.listeners.NodesTinkerListener
import net.aechronis.utils.Command

class TinkerCommand : Command("tinker") {
    init {
        setDefaultExecutor { player, _ ->
            if (NodesTinkerListener.isOn(player)) {
                NodesTinkerListener.set(player, false)
                Message.print(player, "Tinker mode disabled")
            } else if (NodesTinkerListener.set(player, true)) {
                Message.print(player, "Tinker mode enabled: right-click blocks with an empty hand (sneak to interact normally)")
            }
        }
    }
}
