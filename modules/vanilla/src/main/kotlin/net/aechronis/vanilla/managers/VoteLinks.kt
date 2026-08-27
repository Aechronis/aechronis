package net.aechronis.vanilla.managers

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.dialog.Dialog
import net.minestom.server.dialog.DialogAction
import net.minestom.server.dialog.DialogActionButton
import net.minestom.server.dialog.DialogAfterAction
import net.minestom.server.dialog.DialogBody
import net.minestom.server.dialog.DialogMetadata
import net.minestom.server.entity.Player
import java.nio.file.Files
import java.nio.file.Path

object VoteLinks {
    private var links: List<String> = emptyList()

    fun init(file: Path) {
        links =
            if (!Files.exists(file)) {
                emptyList()
            } else {
                runCatching {
                    Files
                        .readAllLines(file)
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .distinct()
                }.getOrElse { error ->
                    System.err.println("Failed to load vote links from $file: ${error.message}")
                    emptyList()
                }
            }
    }

    fun show(player: Player) {
        player.showDialog(
            Dialog.MultiAction(
                DialogMetadata(
                    Component.text("Vote", NamedTextColor.GOLD),
                    null,
                    true,
                    false,
                    DialogAfterAction.CLOSE,
                    listOf(DialogBody.PlainMessage(Component.text("Vote for the server on a website below.", NamedTextColor.GRAY), 300)),
                    emptyList(),
                ),
                links.map(::linkButton),
                DialogActionButton(Component.text("Close", NamedTextColor.GRAY), null, 150, null),
                1,
            ),
        )
    }

    private fun linkButton(link: String) =
        DialogActionButton(
            Component.text(link, NamedTextColor.AQUA),
            Component.text("Open voting website", NamedTextColor.GRAY),
            300,
            DialogAction.OpenUrl(link),
        )
}
