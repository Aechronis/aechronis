package net.aechronis.staff.commands

import net.aechronis.staff.managers.InventoryViews
import net.aechronis.utils.Command
import net.aechronis.vanilla.commands.PlayerTargets
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class InventorySee : Command("invsee", "staff.invsee", "inventorysee") {
    private val playerArg = PlayerTargets.argument()
    private val inventoryArg = ArgumentType.Word("inventory").from("inv", "ec").setDefaultValue("inv")

    init {
        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage:", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/invsee <player> [inv|ec]", NamedTextColor.LIGHT_PURPLE))
        }

        addSyntax({ player: Player, context ->
            val targets = PlayerTargets.resolve(player, context[playerArg]) ?: return@addSyntax
            if (targets.size != 1) {
                player.sendMessage(Component.text("You can only view one inventory at a time.", NamedTextColor.RED))
                return@addSyntax
            }
            val target = targets.single()
            when (context[inventoryArg]) {
                "ec" -> InventoryViews.openEnderChest(player, target)
                else -> InventoryViews.open(player, target)
            }
        }, playerArg, inventoryArg)
    }
}
