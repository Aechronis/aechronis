package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Factories
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class FactoryCommand : Command("factory", "vanilla.factory") {
    private val nameArg = ArgumentType.Word("name")
    private val tierArg = ArgumentType.Integer("tier")

    init {
        setDefaultExecutor { player, _ ->
            player.sendMessage(Component.text("Usage: /factory give <name> <tier> | /factory promote <tier>", NamedTextColor.LIGHT_PURPLE))
        }
        addSyntax({ player: Player, context ->
            val name = context[nameArg]
            val tier = context[tierArg]
            val item = Factories.itemFor(name, tier)
            if (item == null) {
                player.sendMessage(Component.text("Unknown factory or invalid tier.", NamedTextColor.RED))
                return@addSyntax
            }
            if (!player.inventory.addItemStack(item)) player.dropItem(item)
            player.sendMessage(Component.text("Received $name factory (tier $tier).", NamedTextColor.GREEN))
        }, ArgumentType.Literal("give"), nameArg, tierArg)
        addSyntax({ player: Player, context ->
            val result = Factories.promote(player, context[tierArg])
            player.sendMessage(
                Component.text(
                    result ?: "Unable to promote factory.",
                    if (result ==
                        null
                    ) {
                        NamedTextColor.RED
                    } else {
                        NamedTextColor.GREEN
                    },
                ),
            )
        }, ArgumentType.Literal("promote"), tierArg)
    }
}
