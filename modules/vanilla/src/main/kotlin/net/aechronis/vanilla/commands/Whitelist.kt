package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import net.aechronis.vanilla.managers.Whitelist as WhitelistManager

class Whitelist : Command("whitelist", "vanilla.whitelists") {
    init {
        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage:", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/whitelist <toggle|enforce|add|remove> [player] [tier]", NamedTextColor.LIGHT_PURPLE))
        }

        val toggleArg = ArgumentType.Literal("toggle")
        val enforceArg = ArgumentType.Literal("enforce")
        val addArg = ArgumentType.Literal("add")
        val removeArg = ArgumentType.Literal("remove")
        val playerArg = PlayerTargets.argument("player")
        val tierArg =
            ArgumentType
                .Integer("tier")
                .between(
                    WhitelistManager.WEAK_TIER,
                    WhitelistManager.SUPER_ADMIN_TIER,
                ).setDefaultValue(WhitelistManager.WEAK_TIER)

        addSyntax({ sender: Player, _ ->
            val enabled = WhitelistManager.toggle()
            sender.sendMessage(Component.text("Whitelist is now ${if (enabled) "enabled" else "disabled"}", NamedTextColor.LIGHT_PURPLE))
        }, toggleArg)

        addSyntax({ sender: Player, _ ->
            WhitelistManager.enforce()
            sender.sendMessage(Component.text("Whitelist enforced. Non-whitelisted players have been kicked.", NamedTextColor.LIGHT_PURPLE))
        }, enforceArg)

        addSyntax({ sender: Player, context ->
            val name = context[playerArg]
            if (name == "*") {
                val players = MinecraftServer.getConnectionManager().onlinePlayers.toList()
                val tier = context[tierArg]
                players.forEach { WhitelistManager.add(it.username, tier) }
                sender.sendMessage(
                    Component.text("Added ${players.size} player(s) to the tier $tier whitelist", NamedTextColor.LIGHT_PURPLE),
                )
            } else {
                val tier = context[tierArg]
                WhitelistManager.add(name, tier)
                sender.sendMessage(
                    Component.text("Added $name to the tier $tier whitelist", NamedTextColor.LIGHT_PURPLE),
                )
            }
        }, addArg, playerArg, tierArg)

        addSyntax({ sender: Player, context ->
            val name = context[playerArg]
            if (name == "*") {
                val players = MinecraftServer.getConnectionManager().onlinePlayers.toList()
                players.forEach { WhitelistManager.remove(it.username) }
                sender.sendMessage(Component.text("Removed ${players.size} player(s) from the whitelist", NamedTextColor.LIGHT_PURPLE))
            } else {
                WhitelistManager.remove(name)
                sender.sendMessage(Component.text("Removed $name from the whitelist", NamedTextColor.LIGHT_PURPLE))
            }
        }, removeArg, playerArg)
    }
}
