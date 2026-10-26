package net.aechronis.economy.commands

import net.aechronis.economy.BlockShop
import net.aechronis.utils.Command
import net.minestom.server.command.builder.CommandContext
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

/** Opens the block shop, optionally pre-filtered: /blockshop [search terms...]. */
class BlockShopCommand : Command("blockshop", null, "stoneshop") {
    init {
        setDefaultExecutor { player, _ ->
            BlockShop.openRoot(player)
        }

        val queryArg = ArgumentType.StringArray("query")
        addSyntax({ player: Player, context: CommandContext ->
            BlockShop.open(player, folder = null, query = context[queryArg].joinToString(" "))
        }, queryArg)
    }
}
