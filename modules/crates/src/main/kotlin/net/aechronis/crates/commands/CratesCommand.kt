package net.aechronis.crates.commands

import net.aechronis.crates.CrateKeys
import net.aechronis.crates.Crates
import net.aechronis.server.CommandOutput
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.entity.Player

class CratesCommand : Command("crates", null, "keys") {
    private val crateArg =
        ArgumentType.Word("crate").apply {
            setSuggestionCallback { _, _, suggestion -> Crates.ids().forEach { suggestion.addEntry(SuggestionEntry(it)) } }
        }
    private val titleArg = ArgumentType.StringArray("title")
    private val weightArg = ArgumentType.Double("weight").min(0.0)
    private val indexArg = ArgumentType.Integer("index").min(0)
    private val playerArg = ArgumentType.Word("player")
    private val amountArg = ArgumentType.Integer("amount").min(1)

    init {
        setDefaultExecutor { player: Player, _ -> showKeys(player) }
        addSyntax({ player: Player, _ -> showKeys(player) }, ArgumentType.Literal("keys"))

        addSenderSyntax(ADMIN, { sender, _ ->
            sender.reply(if (Crates.ids().isEmpty()) "No crates exist." else "Crates: ${Crates.ids().joinToString(", ")}")
        }, ArgumentType.Literal("list"))

        addSenderSyntax(ADMIN, { sender, context ->
            val id = context[crateArg]
            sender.result(
                Crates.create(id, context[titleArg].joinToString(" ")),
                "Created crate $id. Look at a block and run /crates set $id.",
            )
        }, ArgumentType.Literal("create"), crateArg, titleArg)

        addSenderSyntax(ADMIN, { sender, context ->
            sender.result(Crates.setTitle(context[crateArg], context[titleArg].joinToString(" ")), "Renamed crate.")
        }, ArgumentType.Literal("rename"), crateArg, titleArg)

        addSenderSyntax(ADMIN, { sender, context ->
            sender.result(Crates.delete(context[crateArg]), "Deleted crate ${context[crateArg]}.")
        }, ArgumentType.Literal("delete"), crateArg)

        addSyntax(ADMIN, { player, context ->
            val target = player.getTargetBlockPosition(5)
            val instance = player.instance
            if (target == null || instance == null) {
                player.reply("Look at the block that should become the crate.", NamedTextColor.RED)
                return@addSyntax
            }
            player.result(Crates.place(context[crateArg], instance, target), "That block is now a ${Crates.title(context[crateArg])}.")
        }, ArgumentType.Literal("set"), crateArg)

        addSyntax(ADMIN, { player, _ ->
            val target = player.getTargetBlockPosition(5)
            val instance = player.instance
            if (target == null || instance == null) {
                player.reply("Look at a crate block.", NamedTextColor.RED)
                return@addSyntax
            }
            player.result(Crates.unplace(instance, target), "That block is no longer a crate.")
        }, ArgumentType.Literal("unset"))

        addSyntax(ADMIN, { player, context ->
            player.result(
                Crates.addReward(context[crateArg], player.itemInMainHand, context[weightArg]),
                "Added the held item as a reward.",
            )
        }, ArgumentType.Literal("reward"), ArgumentType.Literal("add"), crateArg, weightArg)

        addSenderSyntax(ADMIN, { sender, context ->
            sender.result(Crates.removeReward(context[crateArg], context[indexArg]), "Removed reward ${context[indexArg]}.")
        }, ArgumentType.Literal("reward"), ArgumentType.Literal("remove"), crateArg, indexArg)

        addSenderSyntax(ADMIN, { sender, context ->
            val rewards = Crates.describeRewards(context[crateArg])
            when {
                rewards == null -> sender.reply("Unknown crate: ${context[crateArg]}", NamedTextColor.RED)
                rewards.isEmpty() -> sender.reply("${context[crateArg]} has no rewards.")
                else -> rewards.forEach { CommandOutput.send(sender, it) }
            }
        }, ArgumentType.Literal("reward"), ArgumentType.Literal("list"), crateArg)

        // Console-capable so KOTH reward commands, Tebex and staff can grant keys.
        addSenderSyntax(ADMIN, { sender, context ->
            val target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(context[playerArg])
            val crate = context[crateArg]
            val amount = context[amountArg]
            when {
                target == null -> sender.reply("${context[playerArg]} is not online.", NamedTextColor.RED)
                !CrateKeys.give(target.uuid, crate, amount) -> sender.reply("Unknown crate: $crate", NamedTextColor.RED)
                else -> {
                    target.reply("You received $amount ${Crates.title(crate)} key${if (amount == 1) "" else "s"}!", NamedTextColor.GREEN)
                    sender.reply("Gave ${target.username} $amount $crate key(s).")
                }
            }
        }, ArgumentType.Literal("give"), playerArg, crateArg, amountArg)

        addSenderSyntax(ADMIN, { sender, context ->
            val target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(context[playerArg])
            if (target == null) {
                sender.reply("${context[playerArg]} is not online.", NamedTextColor.RED)
                return@addSenderSyntax
            }
            val removed = CrateKeys.take(target.uuid, context[crateArg], context[amountArg])
            sender.reply("Took $removed ${context[crateArg]} key(s) from ${target.username}.")
        }, ArgumentType.Literal("take"), playerArg, crateArg, amountArg)
    }

    private fun showKeys(player: Player) {
        val keys = CrateKeys.all(player.uuid)
        if (keys.isEmpty()) {
            player.reply("You don't have any crate keys.")
            return
        }
        player.reply(keys.entries.joinToString(", ") { (crate, count) -> "${Crates.title(crate)}: $count" })
    }

    private fun CommandSender.result(
        error: String?,
        success: String,
    ) = if (error == null) reply(success) else reply(error, NamedTextColor.RED)

    private fun CommandSender.reply(
        text: String,
        color: NamedTextColor = NamedTextColor.GOLD,
    ) = CommandOutput.send(this, Component.text(text, color))

    private companion object {
        const val ADMIN = "crates.admin"
    }
}
