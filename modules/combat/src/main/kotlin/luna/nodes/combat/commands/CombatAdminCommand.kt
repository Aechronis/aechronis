/**
 * Admin commands to manage combat
 * - give guns, ammo
 * - debug models
 *
 *    /combat command ...
 *    /nda command
 */

package luna.nodes.combat.commands

import luna.nodes.combat.commands.arguments.ArgumentItem
import luna.nodes.combat.utils.Message
import net.minestom.server.command.builder.Command
import net.minestom.server.entity.Player

class CombatAdminCommand : Command("combatadmin", "ca") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "[Combat] Admin commands:")
            Message.print(sender, "/combatadmin give$: Give yourself various items")
        }

        addSubcommand(CombatAdminGiveCommand())
    }
}

class CombatAdminGiveCommand : Command("give") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /combatadmin give <item-name>")
        }

        val itemArg = ArgumentItem.create("item-name")

        addSyntax({ sender, context ->
            (sender as Player).inventory.addItemStack(context[itemArg].toItemStack())
        }, itemArg)
    }
}
