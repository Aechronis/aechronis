package net.aechronis.vanilla.commands

import net.aechronis.vanilla.managers.Shop
import net.aechronis.vanilla.utils.Message
import net.aechronis.vanilla.utils.PlayerAddons.hasPermission
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class ShopCommand : Command("shop") {
    init {
        setDefaultExecutor { player, _ ->
            if (!player.hasPermission("vanilla.shop")) {
                Message.error(player, "You don't have permission to use this command")
                return@setDefaultExecutor
            }
            Shop.openShop(player)
        }

        addSyntax({ sender, _ ->
            if (sender !is Player) {
                Message.error(sender, "This command can only be used by players")
                return@addSyntax
            }
            if (!sender.hasPermission("vanilla.admin")) {
                Message.error(sender, "You don't have permission to use this command")
                return@addSyntax
            }
            Shop.restock()
            Message.print(sender, "Shop restocked!")
        }, ArgumentType.Literal("restock"))
    }
}
