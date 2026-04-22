package net.aechronis.vanilla.commands

import net.aechronis.vanilla.commands.Commands.getUser
import net.aechronis.vanilla.utils.Message
import net.kyori.adventure.text.Component
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player

class MuteCommand : Command("mute") {
    private val targetArg = ArgumentType.Entity("target").singleEntity(true).onlyPlayers(true)
    private val reasonArg = ArgumentType.StringArray("reason")

    init {
        addSyntax({ sender, ctx ->
            val instance = if (sender is Player) sender.instance else null
            val target =
                ctx.get(targetArg).findFirstPlayer(instance, sender as? Player) ?: run {
                    Message.error(sender, "Player not found.")
                    return@addSyntax
                }
            val reason = ctx.get(reasonArg).joinToString(" ")

            val targetUser =
                target.getUser() ?: run {
                    Message.error(sender, "An error occurred.")
                    return@addSyntax
                }

            if (targetUser.mutedTime != 0L) {
                Message.error(sender, "${target.username} is already muted.")
                return@addSyntax
            }

            val now = System.currentTimeMillis()
            val newMutes = HashMap(targetUser.mutes)
            newMutes[now] = reason
            Commands.users[target.uuid] = targetUser.copy(mutedTime = now, mutes = newMutes)

            Message.print(sender, "Muted ${target.username}.")
            Message.error(target, "You have been muted.")
        }, targetArg, reasonArg)

        addSyntax({ sender, ctx ->
            val instance = if (sender is Player) sender.instance else null
            val target =
                ctx.get(targetArg).findFirstPlayer(instance, sender as? Player) ?: run {
                    Message.error(sender, "Player not found.")
                    return@addSyntax
                }

            val targetUser =
                target.getUser() ?: run {
                    Message.error(sender, "An error occurred.")
                    return@addSyntax
                }

            if (targetUser.mutedTime != 0L) {
                Message.error(sender, "${target.username} is already muted.")
                return@addSyntax
            }

            val now = System.currentTimeMillis()
            val newMutes = HashMap(targetUser.mutes)
            newMutes[now] = ""
            Commands.users[target.uuid] = targetUser.copy(mutedTime = now, mutes = newMutes)

            Message.print(sender, "Muted ${target.username}.")
            Message.error(target, "You have been muted.")
        }, targetArg)
    }
}

class BanCommand : Command("ban") {
    init {
    }
}

class UnMuteCommand : Command("unmute") {
    private val targetArg = ArgumentType.Entity("target").singleEntity(true).onlyPlayers(true)

    init {
        addSyntax({ sender, ctx ->
            val instance = if (sender is Player) sender.instance else null
            val target =
                ctx.get(targetArg).findFirstPlayer(instance, sender as? Player) ?: run {
                    Message.error(sender, "Player not found.")
                    return@addSyntax
                }

            val targetUser =
                target.getUser() ?: run {
                    Message.error(sender, "An error occurred.")
                    return@addSyntax
                }

            if (targetUser.mutedTime == 0L) {
                Message.error(sender, "${target.username} is not muted.")
                return@addSyntax
            }

            Commands.users[target.uuid] = targetUser.copy(mutedTime = 0L)

            Message.print(sender, "Unmuted ${target.username}.")
            Message.print(target, "You have been unmuted.")
        }, targetArg)
    }
}

class UnBanCommand : Command("unban") {
    init {
    }
}

class GameModeCommand : Command("gmc", "gms", "gma", "gmsp") {
    private val targetArg = ArgumentType.Entity("target").singleEntity(true).onlyPlayers(true)

    init {
        addSyntax({ sender, ctx ->
            val instance = if (sender is Player) sender.instance else null
            val target =
                ctx.get(targetArg).findFirstPlayer(instance, sender as? Player) ?: run {
                    Message.error(sender, "Player not found.")
                    return@addSyntax
                }

            applyGameMode(sender, target, ctx.commandName)
        }, targetArg)

        addSyntax({ sender, ctx ->
            if (sender !is Player) {
                Message.error(sender, "Must be a player.")
                return@addSyntax
            }

            applyGameMode(sender, sender, ctx.commandName)
        })
    }

    private fun applyGameMode(
        sender: CommandSender,
        target: Player,
        command: String,
    ) {
        val mode =
            when (command) {
                "gmc" -> GameMode.CREATIVE
                "gms" -> GameMode.SURVIVAL
                "gma" -> GameMode.ADVENTURE
                "gmsp" -> GameMode.SPECTATOR
                else -> return
            }

        target.gameMode = mode
        Message.print(
            sender,
            "Set ${target.username}'s game mode to ${mode.name.lowercase().replaceFirstChar { it.uppercase() }}.",
        )
    }
}

class FlyCommand : Command("fly") {
    init {
        addSyntax({ sender, ctx ->
            if (sender !is Player) {
                Message.error(sender, "Must be a player.")
                return@addSyntax
            }

            sender.isFlying = !sender.isFlying
            Message.print(sender, "Fly mode ${if (sender.isFlying) "enabled" else "disabled"}.")
        })
    }
}

class GiveCommand : Command("give") {
    private val itemArg = ArgumentType.ItemStack("item")
    private val amountArg = ArgumentType.Integer("amount").between(1, 64)

    init {
        addSyntax({ sender, ctx ->
            if (sender !is Player) {
                Message.error(sender, "Must be a player.")
                return@addSyntax
            }

            val item = ctx.get(itemArg).withAmount(ctx.get(amountArg))
            sender.inventory.addItemStack(item)
            Message.print(sender, "Given ${item.amount()}x ${item.material().name()}")
        }, itemArg, amountArg)

        addSyntax({ sender, ctx ->
            if (sender !is Player) {
                Message.error(sender, "Must be a player.")
                return@addSyntax
            }

            val item = ctx.get(itemArg)
            sender.inventory.addItemStack(item)
            sender.sendMessage(Component.text("Given 1x ${item.material().name()}"))
        }, itemArg)
    }
}

class TeleportCommand : Command("teleport", "tp") {
    private val targetArg = ArgumentType.Entity("target").singleEntity(true).onlyPlayers(true)
    private val toPlayerArg = ArgumentType.Entity("to").singleEntity(true).onlyPlayers(true)
    private val posArg = ArgumentType.RelativeVec3("pos")

    init {
        // player teleports to coordinates
        addSyntax({ sender, ctx ->
            val target =
                ctx.get(targetArg).findFirstPlayer(sender as? Player) ?: run {
                    Message.error(sender, "Player not found.")
                    return@addSyntax
                }

            val pos = ctx.get(posArg).from(target.position)
            target.teleport(Pos(pos.x(), pos.y(), pos.z(), target.position.yaw, target.position.pitch))
        }, targetArg, posArg)

        // player teleports to another player
        addSyntax({ sender, ctx ->
            val instance = if (sender is Player) sender.instance else null
            val self = sender as? Player

            val target =
                ctx.get(targetArg).findFirstPlayer(instance, self) ?: run {
                    Message.error(sender, "player not found.")
                    return@addSyntax
                }

            val location =
                ctx.get(toPlayerArg).findFirstPlayer(instance, self) ?: run {
                    Message.error(sender, "player not found.")
                    return@addSyntax
                }

            target.teleport(location.position)
        }, targetArg, toPlayerArg)

        // sender teleports to target
        addSyntax({ sender, ctx ->
            if (sender !is Player) return@addSyntax Message.error(sender, "must be player.")

            val location =
                ctx.get(targetArg).findFirstPlayer(sender.instance, sender) ?: run {
                    Message.error(sender, "player not found.")
                    return@addSyntax
                }

            sender.teleport(location.position)
        }, targetArg)
    }
}
