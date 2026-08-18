package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.minestom.server.entity.Player

class Kill : Command("kill", "vanilla.kill") {
    private val playerArg = PlayerTargets.argument()

    init {
        setDefaultExecutor { player: Player, _ ->
            player.kill()
        }

        addSyntax({ sender: Player, context ->
            val targets = PlayerTargets.resolve(sender, context[playerArg]) ?: return@addSyntax
            targets.forEach(Player::kill)
            sender.sendMessage("Killed ${targets.size} player(s).")
        }, playerArg)
    }
}
