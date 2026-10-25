package net.aechronis.killshop.commands

import net.aechronis.killshop.KillShop
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType

class Shop : Command("shop") {
    init {
        setDefaultExecutor("vanilla.shop") { player, _ ->
            KillShop.openShop(player)
        }

        addSyntax("vanilla.admin", { sender, _ ->
            KillShop.restock()
            sender.sendMessage(Component.text("Shop restocked!", NamedTextColor.LIGHT_PURPLE))
        }, ArgumentType.Literal("restock"))
    }
}
