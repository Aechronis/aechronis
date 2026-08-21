package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Koth
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class KothCommand : Command("koth", "vanilla.koth") {
    private val nameArg = ArgumentType.Word("name")
    private val captureSecondsArg = ArgumentType.Long("capture-seconds").min(1)
    private val eventSecondsArg = ArgumentType.Long("event-seconds").min(1)
    private val radiusArg = ArgumentType.Double("display-radius").min(0.01)
    private val rewardArg = ArgumentType.StringArray("command")
    private val indexArg = ArgumentType.Integer("index").min(0)
    private val timeArg = ArgumentType.Word("HH:mm")

    init {
        setDefaultExecutor { sender: Player, _ -> sendList(sender) }
        addSyntax("vanilla.koth", { sender: Player, _ -> sendList(sender) }, ArgumentType.Literal("list"))

        addSyntax("vanilla.koth.admin", { sender: Player, context ->
            val name = context[nameArg]
            val created = Koth.add(name, context[captureSecondsArg], context[eventSecondsArg], context[radiusArg])
            sender.message(
                if (created) "Created KOTH $name. Set its corners with /koth pos1 and /koth pos2." else "Unable to create KOTH $name.",
            )
        }, ArgumentType.Literal("add"), nameArg, captureSecondsArg, eventSecondsArg, radiusArg)

        addSyntax("vanilla.koth.admin", { sender: Player, context ->
            val name = context[nameArg]
            sender.message(
                if (Koth.setCorner(name, sender, true)) "Set first corner for KOTH $name." else "Unable to set the first corner for $name.",
            )
        }, ArgumentType.Literal("pos1"), nameArg)

        addSyntax("vanilla.koth.admin", { sender: Player, context ->
            val name = context[nameArg]
            sender.message(
                if (Koth.setCorner(
                        name,
                        sender,
                        false,
                    )
                ) {
                    "Set second corner for KOTH $name."
                } else {
                    "Unable to set the second corner for $name."
                },
            )
        }, ArgumentType.Literal("pos2"), nameArg)

        addSyntax("vanilla.koth.admin", { sender: Player, context ->
            val name = context[nameArg]
            sender.message(if (Koth.remove(name)) "Removed KOTH $name." else "Unable to remove KOTH $name. Stop it first.")
        }, ArgumentType.Literal("remove"), nameArg)

        addSyntax("vanilla.koth.admin", { sender: Player, context ->
            val name = context[nameArg]
            sender.message(if (Koth.start(name)) "Started KOTH $name." else "Unable to start KOTH $name. Check its world and corners.")
        }, ArgumentType.Literal("start"), nameArg)

        addSyntax("vanilla.koth.admin", { sender: Player, context ->
            val name = context[nameArg]
            sender.message(if (Koth.stop(name)) "Stopped KOTH $name." else "KOTH $name is not active.")
        }, ArgumentType.Literal("stop"), nameArg)

        addSyntax("vanilla.koth.admin", { sender: Player, context ->
            val name = context[nameArg]
            sender.message(
                if (Koth.addReward(
                        name,
                        context[rewardArg].joinToString(" "),
                    )
                ) {
                    "Added reward command to KOTH $name."
                } else {
                    "Unable to add that reward command."
                },
            )
        }, ArgumentType.Literal("reward"), ArgumentType.Literal("add"), nameArg, rewardArg)

        addSyntax("vanilla.koth.admin", { sender: Player, context ->
            val name = context[nameArg]
            sender.message(if (Koth.removeReward(name, context[indexArg])) "Removed reward command." else "Unknown reward command index.")
        }, ArgumentType.Literal("reward"), ArgumentType.Literal("remove"), nameArg, indexArg)

        addSyntax("vanilla.koth.admin", { sender: Player, context ->
            val name = context[nameArg]
            val rewards = Koth.rewards(name)
            sender.message(rewards?.mapIndexed { index, command -> "$index: $command" }?.joinToString(" | ") ?: "Unknown KOTH: $name")
        }, ArgumentType.Literal("reward"), ArgumentType.Literal("list"), nameArg)

        addSyntax("vanilla.koth.admin", { sender: Player, context ->
            val name = context[nameArg]
            sender.message(
                if (Koth.addSchedule(name, context[timeArg])) "Added schedule for $name." else "Unable to add schedule; use HH:mm.",
            )
        }, ArgumentType.Literal("schedule"), ArgumentType.Literal("add"), nameArg, timeArg)

        addSyntax("vanilla.koth.admin", { sender: Player, context ->
            val name = context[nameArg]
            sender.message(
                if (Koth.removeSchedule(name, context[timeArg])) "Removed schedule for $name." else "Unknown schedule; use HH:mm.",
            )
        }, ArgumentType.Literal("schedule"), ArgumentType.Literal("remove"), nameArg, timeArg)

        addSyntax("vanilla.koth.admin", { sender: Player, context ->
            val name = context[nameArg]
            sender.message(Koth.schedules(name)?.joinToString(", ") ?: "Unknown KOTH: $name")
        }, ArgumentType.Literal("schedule"), ArgumentType.Literal("list"), nameArg)

        addSyntax("vanilla.koth", { sender: Player, _ -> sendStatuses(sender) }, ArgumentType.Literal("status"))
        addSyntax("vanilla.koth", { sender: Player, context ->
            val name = context[nameArg]
            sender.message(Koth.status(name) ?: "Unknown KOTH: $name")
        }, ArgumentType.Literal("status"), nameArg)
    }

    private fun sendList(sender: Player) {
        val names = Koth.configuredNames()
        sender.message(if (names.isEmpty()) "No KOTHS are configured." else "KOTHS: ${names.joinToString(", ")}")
    }

    private fun sendStatuses(sender: Player) {
        val names = Koth.configuredNames()
        if (names.isEmpty()) {
            sender.message("No KOTHS are configured.")
            return
        }
        names.forEach { name -> sender.message(Koth.status(name)!!) }
    }

    private fun Player.message(text: String) = sendMessage(Component.text(text, NamedTextColor.LIGHT_PURPLE))
}
