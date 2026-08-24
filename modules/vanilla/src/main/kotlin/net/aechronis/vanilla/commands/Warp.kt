package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Warps
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Warp : Command("warp", "vanilla.warp") {
    private val nameArg = ArgumentType.Word("name")

    init {
        setDefaultExecutor { player: Player, _ ->
            val names = Warps.names()
            player.message(if (names.isEmpty()) "No warps are configured." else "Warps: ${names.joinToString(", ")}")
        }

        addSyntax("vanilla.warp.admin", { sender: Player, context ->
            val name = context[nameArg]
            if (Warps.set(name, sender)) sender.message("Set warp $name.") else sender.error("Unable to set warp $name.")
        }, ArgumentType.Literal("set"), nameArg)

        addSyntax("vanilla.warp.admin", { sender: Player, context ->
            val name = context[nameArg]
            if (Warps.remove(name)) sender.message("Removed warp $name.") else sender.error("Unknown warp: $name")
        }, ArgumentType.Literal("remove"), nameArg)

        addSyntax({ sender: Player, _ ->
            val names = Warps.names()
            sender.message(if (names.isEmpty()) "No warps are configured." else "Warps: ${names.joinToString(", ")}")
        }, ArgumentType.Literal("list"))

        addSyntax({ sender: Player, context ->
            when (Warps.teleport(sender, context[nameArg]) { name -> sender.message("Warped to $name.") }) {
                Warps.TeleportResult.SUCCESS -> sender.message("Warped to ${context[nameArg]}.")
                Warps.TeleportResult.WARPING -> {
                    val delay = Vanilla.config.warpCooldownSeconds.coerceAtLeast(0)
                    sender.message("Warping to ${context[nameArg]} in ${delay}s.")
                }
                Warps.TeleportResult.ALREADY_WARPING -> sender.error("You are already waiting to warp.")
                Warps.TeleportResult.NOT_FOUND -> sender.error("Unknown warp: ${context[nameArg]}")
                Warps.TeleportResult.WORLD_UNAVAILABLE -> sender.error("That warp's world is not available.")
                Warps.TeleportResult.ON_COOLDOWN -> {
                    val remaining = (Warps.remainingCooldownMillis(sender) + 999) / 1_000
                    sender.error("You must wait ${remaining}s before warping again.")
                }
            }
        }, nameArg)
    }

    private fun Player.message(message: String) = sendMessage(Component.text(message, NamedTextColor.LIGHT_PURPLE))

    private fun Player.error(message: String) = sendMessage(Component.text(message, NamedTextColor.RED))
}
