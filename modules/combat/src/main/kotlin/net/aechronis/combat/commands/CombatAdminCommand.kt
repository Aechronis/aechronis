/**
 * Admin commands to manage combat
 * - give guns, ammo
 * - debug models
 *
 *    /combat command ...
 *    /nda command
 */

package net.aechronis.combat.commands

import net.aechronis.combat.commands.arguments.ArgumentHat
import net.aechronis.combat.commands.arguments.ArgumentItem
import net.aechronis.combat.objects.Explosion
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.storage.HatCollection
import net.aechronis.combat.utils.Message
import net.aechronis.combat.utils.hasPermission
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.arguments.minecraft.ArgumentEntity
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
            Message.print(sender, "/combatadmin hat: Manage player hat collections")
        }

        addSubcommand(CombatAdminGiveCommand())
        addSubcommand(CombatAdminExplosionCommand())
        addSubcommand(CombatAdminHitboxCommand())
        addSubcommand(CombatAdminHatCommand())
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

class CombatAdminHatCommand : Command("hat") {
    init {
        setDefaultExecutor { sender, _ ->
            if (!hasPermission((sender as Player), "combat.admin")) {
                return@setDefaultExecutor
            }

            Message.print(sender, "Usage:")
            Message.print(sender, "/combatadmin hat give <player-name> <hat-name>")
            Message.print(sender, "/combatadmin hat remove <player-name> <hat-name>")
        }

        addSubcommand(CombatAdminHatGiveCommand())
        addSubcommand(CombatAdminHatRemoveCommand())
    }
}

class CombatAdminHatGiveCommand : Command("give") {
    init {
        val playerArg = ArgumentEntity("player-name").onlyPlayers(true).singleEntity(true)
        val hatArg = ArgumentHat.create("hat-name")

        setDefaultExecutor { sender, _ ->
            if (!hasPermission((sender as Player), "combat.admin")) {
                return@setDefaultExecutor
            }
            Message.print(sender, "Usage: /combatadmin hat give <player-name> <hat-name>")
        }

        addSyntax({ sender, context ->
            if (!hasPermission((sender as Player), "combat.admin")) {
                return@addSyntax
            }

            val target =
                context[playerArg].findFirstPlayer(sender) ?: run {
                    Message.print(sender, "Player not found")
                    return@addSyntax
                }
            val hat = context[hatArg]

            HatCollection.give(target.uuid, hat)
            Message.print(sender, "Gave hat '${hat.name}' to ${target.username}")
        }, playerArg, hatArg)
    }
}

class CombatAdminHatRemoveCommand : Command("remove") {
    init {
        val playerArg = ArgumentEntity("player-name").onlyPlayers(true).singleEntity(true)
        val hatArg = ArgumentHat.create("hat-name")

        setDefaultExecutor { sender, _ ->
            if (!hasPermission((sender as Player), "combat.admin")) {
                return@setDefaultExecutor
            }
            Message.print(sender, "Usage: /combatadmin hat remove <player-name> <hat-name>")
        }

        addSyntax({ sender, context ->
            if (!hasPermission((sender as Player), "combat.admin")) {
                return@addSyntax
            }

            val target =
                context[playerArg].findFirstPlayer(sender) ?: run {
                    Message.print(sender, "Player not found")
                    return@addSyntax
                }
            val hat = context[hatArg]

            HatCollection.remove(target.uuid, hat)
            Message.print(sender, "Removed hat '${hat.name}' from ${target.username}")
        }, playerArg, hatArg)
    }
}
