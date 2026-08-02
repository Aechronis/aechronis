package net.aechronis.logger.commands

import net.aechronis.logger.Logger
import net.aechronis.logger.objects.RollbackActor
import net.aechronis.logger.utils.RollbackExecution
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player

class Undo : Command("undo", "logger.undo") {
    init {
        setDefaultExecutor { sender: Player, _ ->
            sender.sendMessage(Component.text("[Logger] undo started...", NamedTextColor.GRAY))
            Logger.rollbackService.undoAsync(RollbackActor(sender.uuid, sender.username)).whenComplete { result, failure ->
                if (failure == null) {
                    RollbackExecution.reportSuccess(sender, result, "Undo")
                } else {
                    RollbackExecution.reportFailure(sender, "undo failed", failure)
                }
            }
        }
    }
}
