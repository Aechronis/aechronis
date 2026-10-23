package net.aechronis.nodes.commands

import net.aechronis.nodes.objects.BlockShop
import net.aechronis.nodes.objects.NodesCommand
import net.minestom.server.command.builder.arguments.ArgumentType

/** Opens the block shop, optionally pre-filtered: /blockshop [search terms...]. */
class BlockShopCommand : NodesCommand("blockshop", null, "shop") {
    init {
        setDefaultExecutor { player, resident, _ ->
            BlockShop.openRoot(player, resident)
        }

        val queryArg = ArgumentType.StringArray("query")
        addSyntax({ player, resident, context ->
            BlockShop.open(player, resident, category = null, query = context[queryArg].joinToString(" "))
        }, queryArg)
    }
}
