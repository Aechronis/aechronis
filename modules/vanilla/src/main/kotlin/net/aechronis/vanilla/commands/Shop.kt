package net.aechronis.vanilla.commands

import net.aechronis.vanilla.managers.KillShop
import net.aechronis.vanilla.utils.Command
import net.aechronis.vanilla.utils.Message
import net.aechronis.vanilla.utils.PlayerAddons.hasPermission
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Shop : Command("shop") {
    init {
        setDefaultExecutor { player: Player, _ ->
            if (!player.hasPermission("vanilla.shop")) {
                Message.error(player, "You don't have permission to use this command")
                return@setDefaultExecutor
            }
            KillShop.openShop(player)
        }

        addSyntax({ sender: Player, _ ->
            if (!sender.hasPermission("vanilla.admin")) {
                Message.error(sender, "You don't have permission to use this command")
                return@addSyntax
            }
            KillShop.restock()
            Message.print(sender, "Shop restocked!")
        }, ArgumentType.Literal("restock"))
    }
}
