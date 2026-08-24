package net.aechronis.logger.commands

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

class LoggerCommand : Command("logger", null, "lo") {
    init {
        setDefaultExecutor { sender, _ ->
            sender.sendMessage(
                Component.text(
                    "Usage: /logger inspect | lookup | snapshot | rollback | restore | restorechunk | undo | redo | safety | recover",
                    NamedTextColor.GOLD,
                ),
            )
        }
        addSubcommand(LoggerInspectCommand())
        addSubcommand(LoggerLookupCommand())
        addSubcommand(Page())
        addSubcommand(Snapshot())
        addSubcommand(Rollback())
        addSubcommand(Restore())
        addSubcommand(RestoreChunk())
        addSubcommand(Undo())
        addSubcommand(Redo())
        addSubcommand(Safety())
        addSubcommand(Recover())
    }
}
