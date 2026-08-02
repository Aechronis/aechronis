package net.aechronis.logger.commands

import net.aechronis.logger.objects.SnapshotViewer
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.entity.Player

class Snapshot : Command("snapshot", "logger.snapshot") {
    init {
        setDefaultExecutor { sender, _ ->
            sender.sendMessage(Component.text("Usage: /logger snapshot <ign>", NamedTextColor.GOLD))
        }
        val ign =
            ArgumentType.Word("ign").setSuggestionCallback { _, _, suggestion ->
                MinecraftServer
                    .getConnectionManager()
                    .onlinePlayers
                    .map(Player::getUsername)
                    .sorted()
                    .forEach { suggestion.addEntry(SuggestionEntry(it)) }
            }
        addSyntax({ sender: Player, context -> SnapshotViewer.openList(sender, context.get(ign)) }, ign)
    }
}
