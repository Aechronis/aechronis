package net.aechronis.discord

import net.aechronis.utils.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

internal class DiscordAccountCommand(
    links: AccountLinks,
) : Command("discord") {
    init {
        setDefaultExecutor { player: Player, _ ->
            val id = links.discord(player.uuid)
            player.sendMessage(
                if (id ==
                    null
                ) {
                    "Not linked. Use /discord link."
                } else {
                    "Linked to Discord account $id. Use /discord unlink to revoke access."
                },
            )
        }
        addSyntax({ player: Player, _ ->
            try {
                val code = links.issue(player.uuid)
                player.sendMessage("In Discord, use /link code:$code within 5 minutes. Keep this code private.")
            } catch (error: IllegalStateException) {
                player.sendMessage(error.message ?: "Could not create a link code.")
            }
        }, ArgumentType.Literal("link"))
        addSyntax({ player: Player, _ ->
            try {
                links.unlinkPlayer(player.uuid)
                player.sendMessage("Discord access revoked; pending link codes cancelled.")
            } catch (_: Exception) {
                player.sendMessage("Could not save account links. Access has not changed; contact an administrator.")
            }
        }, ArgumentType.Literal("unlink"))
    }
}
