package net.aechronis.vanilla.essentials.commands

import net.aechronis.vanilla.Main
import net.aechronis.vanilla.essentials.Essentials
import net.aechronis.vanilla.essentials.Essentials.getUser
import net.aechronis.vanilla.utils.ChatColor
import net.aechronis.vanilla.utils.Message
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentStringArray
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.arguments.minecraft.ArgumentEntity
import net.minestom.server.entity.Player

class MessageCommand : Command("message", "msg", "tell", "w") {
    val targetArg: ArgumentEntity? = ArgumentType.Entity("target").singleEntity(true).onlyPlayers(true)
    val messageArg: ArgumentStringArray? = ArgumentType.StringArray("message")

    init {
        addSyntax({ sender, ctx ->
            if (sender !is Player) return@addSyntax
            val receiver =
                ctx.get(targetArg).findFirstPlayer(sender.instance, sender) ?: run {
                    Message.error(sender, "Player not found.")
                    return@addSyntax
                }
            val message = ctx.get(messageArg).joinToString(" ")

            if (sender.uuid == receiver.uuid) {
                Message.print(sender, "${ChatColor.AQUA}You: ${ChatColor.WHITE}$message")
                return@addSyntax
            }

            val receiverUser =
                receiver.getUser() ?: run {
                    Message.error(sender, "An error occurred while sending the message.")
                    return@addSyntax
                }

            if (receiverUser.ignored.contains(sender.uuid)) {
                Message.error(sender, "You cannot send a message to this player.")
                return@addSyntax
            }

            Message.print(
                sender,
                "${ChatColor.AQUA}You ${ChatColor.WHITE}-> ${ChatColor.AQUA}${receiver.username}: ${ChatColor.WHITE}$message",
            )

            Message.print(
                receiver,
                "${ChatColor.AQUA}${sender.username} ${ChatColor.WHITE}-> ${ChatColor.AQUA}You: ${ChatColor.WHITE}$message",
            )

            val senderUser = sender.getUser() ?: return@addSyntax
            Essentials.users[sender.uuid] = senderUser.copy(lastMessage = receiver.uuid)
            Essentials.users[receiver.uuid] = receiverUser.copy(lastMessage = sender.uuid)
        }, targetArg, messageArg)
    }
}

class ReplyCommand : Command("reply", "r") {
    val messageArg = ArgumentType.StringArray("message")

    init {
        addSyntax({ sender, ctx ->
            if (sender !is Player) return@addSyntax
            val message = ctx.get(messageArg).joinToString(" ")

            val senderUser =
                sender.getUser() ?: run {
                    Message.error(sender, "An error occurred.")
                    return@addSyntax
                }

            val lastMessageUuid =
                senderUser.lastMessage ?: run {
                    Message.error(sender, "You have no one to reply to.")
                    return@addSyntax
                }

            val target =
                MinecraftServer
                    .getConnectionManager()
                    .getOnlinePlayers()
                    .find { it.uuid == lastMessageUuid } ?: run {
                    Message.error(sender, "That player is no longer online.")
                    return@addSyntax
                }

            val targetUser =
                target.getUser() ?: run {
                    Message.error(sender, "An error occurred while sending the message.")
                    return@addSyntax
                }

            if (targetUser.ignored.contains(sender.uuid)) {
                Message.error(sender, "You cannot send a message to this player.")
                return@addSyntax
            }

            Message.print(
                sender,
                "${ChatColor.AQUA}You ${ChatColor.WHITE}-> ${ChatColor.AQUA}${target.username}: ${ChatColor.WHITE}$message",
            )

            Message.print(
                target,
                "${ChatColor.AQUA}${sender.username} ${ChatColor.WHITE}-> ${ChatColor.AQUA}You: ${ChatColor.WHITE}$message",
            )

            Essentials.users[sender.uuid] = senderUser.copy(lastMessage = target.uuid)
            Essentials.users[target.uuid] = targetUser.copy(lastMessage = sender.uuid)
        }, messageArg)
    }
}

class IgnoreCommand : Command("ignore") {
    val targetArg = ArgumentType.Entity("target").singleEntity(true).onlyPlayers(true)

    init {
        addSyntax({ sender, ctx ->
            if (sender !is Player) return@addSyntax
            val target =
                ctx.get(targetArg).findFirstPlayer(sender.instance, sender) ?: run {
                    Message.error(sender, "Player not found.")
                    return@addSyntax
                }

            if (sender.uuid == target.uuid) {
                Message.error(sender, "You cannot ignore yourself.")
                return@addSyntax
            }

            val senderUser =
                sender.getUser() ?: run {
                    Message.error(sender, "An error occurred.")
                    return@addSyntax
                }

            val newIgnored = HashSet(senderUser.ignored)
            if (newIgnored.remove(target.uuid)) {
                Essentials.users[sender.uuid] = senderUser.copy(ignored = newIgnored)
                Message.print(sender, "You are no longer ignoring ${target.username}.")
            } else {
                newIgnored.add(target.uuid)
                Essentials.users[sender.uuid] = senderUser.copy(ignored = newIgnored)
                Message.print(sender, "You are now ignoring ${target.username}.")
            }
        }, targetArg)
    }
}
