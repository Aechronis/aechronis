/**
 * Admin commands to manage combat
 * - give guns, ammo
 * - debug models
 *
 *    /combat command ...
 *    /nda command
 */

package net.aechronis.combat.commands

import net.aechronis.combat.commands.arguments.ArgumentItem
import net.aechronis.combat.objects.Explosion
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.utils.Message
import net.aechronis.combat.utils.hasPermission
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
            Message.print(sender, "/combatadmin hitbox: Toggle hitbox visualization")
        }

        addSubcommand(CombatAdminGiveCommand())
        addSubcommand(CombatAdminExplosionCommand())
        addSubcommand(CombatAdminHitboxCommand())
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

class CombatAdminHitboxCommand : Command("hitbox") {
    init {
        setDefaultExecutor { sender, _ ->
            if (!hasPermission((sender as Player), "combat.admin")) {
                return@setDefaultExecutor
            }

            if (Hitbox.viewingHitboxes.contains(sender)) {
                Hitbox.viewingHitboxes.remove(sender)
                Message.print(sender, "Hitbox visualization disabled")
            } else {
                Hitbox.viewingHitboxes.add(sender)
                Message.print(sender, "Hitbox visualization enabled")
            }
        }
    }
}
