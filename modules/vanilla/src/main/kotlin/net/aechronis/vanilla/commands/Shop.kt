package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.KillShop
import net.aechronis.vanilla.utils.Message
import net.minestom.server.command.builder.arguments.ArgumentType

class Shop : Command("shop") {
    init {
        setDefaultExecutor("vanilla.shop") { player, _ ->
            KillShop.openShop(player)
        }

        addSyntax("vanilla.admin", { sender, _ ->
            KillShop.restock()
            Message.print(sender, "Shop restocked!")
        }, ArgumentType.Literal("restock"))
    }
}
