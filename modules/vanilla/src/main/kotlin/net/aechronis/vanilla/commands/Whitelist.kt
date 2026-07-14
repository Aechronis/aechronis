package net.aechronis.vanilla.commands

import net.aechronis.vanilla.utils.Message
import net.aechronis.vanilla.utils.VanillaCommand
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import net.aechronis.vanilla.managers.Whitelist as WhitelistManager

class Whitelist : VanillaCommand("whitelist", "vanilla.whitelist") {
    init {
        setDefaultExecutor { player: Player, _ ->
            Message.print(player, "Usage:")
            Message.print(player, "/whitelist <toggle|enforce|add|remove> [player]")
        }

        val toggleArg = ArgumentType.Literal("toggle")
        val enforceArg = ArgumentType.Literal("enforce")
        val addArg = ArgumentType.Literal("add")
        val removeArg = ArgumentType.Literal("remove")
        val playerArg = ArgumentType.Word("player")

        addSyntax({ sender: Player, _ ->
            val enabled = WhitelistManager.toggle()
            Message.print(sender, "Whitelist is now ${if (enabled) "enabled" else "disabled"}")
        }, toggleArg)

        addSyntax({ sender: Player, _ ->
            WhitelistManager.enforce()
            Message.print(sender, "Whitelist enforced. Non-whitelisted players have been kicked.")
        }, enforceArg)

        addSyntax({ sender: Player, context ->
            val name = context[playerArg]
            WhitelistManager.add(name)
            Message.print(sender, "Added $name to the whitelist")
        }, addArg, playerArg)

        addSyntax({ sender: Player, context ->
            val name = context[playerArg]
            WhitelistManager.remove(name)
            Message.print(sender, "Removed $name from the whitelist")
        }, removeArg, playerArg)
    }
}
