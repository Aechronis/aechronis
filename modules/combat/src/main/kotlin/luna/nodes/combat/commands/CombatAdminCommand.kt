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
import luna.nodes.combat.objects.Explosion
import luna.nodes.combat.objects.Projectile
import luna.nodes.combat.utils.Message
import luna.nodes.nodes.utils.hasPermission
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class CombatAdminCommand : Command("combatadmin", "ca") {
    init {
        setDefaultExecutor { sender, context ->
            if (!hasPermission((sender as Player), "combat.admin")) {
                return@setDefaultExecutor
            }

            Message.print(sender, "[Combat] Admin commands:")
            Message.print(sender, "/combatadmin give: Give yourself various items")
            Message.print(sender, "/combatadmin explosion: Make an explosion")
        }

        addSubcommand(CombatAdminGiveCommand())
        addSubcommand(CombatAdminExplosionCommand())
        addSubcommand(CombatAdminProjectileCommand())
    }
}

class CombatAdminGiveCommand : Command("give") {
    init {
        setDefaultExecutor { sender, context ->
            if (!hasPermission((sender as Player), "combat.admin")) {
                return@setDefaultExecutor
            }

            Message.print(sender, "Usage: /combatadmin give <item-name>")
        }

        val itemArg = ArgumentItem.create("item-name")

        addSyntax({ sender, context ->
            if (!hasPermission((sender as Player), "combat.admin")) {
                return@addSyntax
            }

            sender.inventory.addItemStack(context[itemArg].toItemStack())
        }, itemArg)
    }
}

class CombatAdminExplosionCommand : Command("explosion") {
    init {
        setDefaultExecutor { sender, context ->
            if (!hasPermission((sender as Player), "combat.admin")) {
                return@setDefaultExecutor
            }

            Message.print(sender, "Usage: /combatadmin explosion <radius> <fire>")
        }

        val radisuArg = ArgumentType.Integer("radius")
        val fireArg = ArgumentType.Double("fire")

        addSyntax({ sender, context ->
            if (!hasPermission((sender as Player), "combat.admin")) {
                return@addSyntax
            }

            Explosion(sender.instance, sender.position, context[radisuArg], context[fireArg])
        }, radisuArg, fireArg)
    }
}
