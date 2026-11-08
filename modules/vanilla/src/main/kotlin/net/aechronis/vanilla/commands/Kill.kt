package net.aechronis.vanilla.commands

import net.aechronis.combat.listeners.PlayerDeathListener
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.minestom.server.entity.Player

class Kill : Command("kill", "vanilla.kill") {
    private val playerArg = PlayerTargets.argument()

    init {
        setDefaultExecutor { player: Player, _ ->
            kill(player)
        }

        addSyntax({ sender: Player, context ->
            val targets = PlayerTargets.resolve(sender, context[playerArg]) ?: return@addSyntax
            targets.forEach(::kill)
            sender.sendMessage("Killed ${targets.size} player(s).")
        }, playerArg)
    }

    private fun kill(player: Player) {
        PlayerDeathListener.killWithMessage(player, Component.translatable("death.attack.genericKill", player.name))
    }
}
