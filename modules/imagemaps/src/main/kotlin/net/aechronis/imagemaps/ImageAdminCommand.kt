package net.aechronis.imagemaps

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.entity.Player

/** Staff moderation for the public gallery; grant [ImageMaps.ADMIN_PERMISSION] in LuckPerms. */
class ImageAdminCommand : Command("imageadmin", ImageMaps.ADMIN_PERMISSION) {
    private val playerArg =
        ArgumentType.Word("player").setSuggestionCallback { _, _, suggestion ->
            val prefix = suggestion.input.substringAfterLast(' ')
            ImageMaps
                .all()
                .map(ImageMaps.Image::ownerLabel)
                .distinct()
                .filter { it.startsWith(prefix, ignoreCase = true) }
                .forEach { suggestion.addEntry(SuggestionEntry(it)) }
        }
    private val nameArg =
        ArgumentType.Word("name").setSuggestionCallback { _, _, suggestion ->
            val words = suggestion.input.split(' ')
            val owner = words.getOrNull(words.size - 2).orEmpty()
            ImageMaps
                .byOwnerName(owner)
                .map(ImageMaps.Image::name)
                .filter { it.startsWith(words.last(), ignoreCase = true) }
                .forEach { suggestion.addEntry(SuggestionEntry(it)) }
        }

    init {
        setDefaultExecutor { player: Player, _ -> player.message("Usage: /imageadmin list <player> | /imageadmin delete <player> <name>") }

        addSyntax({ player: Player, context ->
            val owner = context[playerArg]
            val images = ImageMaps.byOwnerName(owner)
            player.message(
                if (images.isEmpty()) {
                    "$owner has no images."
                } else {
                    images.joinToString(prefix = "$owner: ") { "${it.name} (${it.cols}x${it.rows})" }
                },
            )
        }, ArgumentType.Literal("list"), playerArg)

        addSyntax({ player: Player, context ->
            val owner = context[playerArg]
            val name = context[nameArg]
            try {
                val image = ImageMaps.delete(owner, name)
                if (image == null) {
                    player.error("$owner has no image named $name.")
                } else {
                    player.message("Deleted ${image.name} (${image.cols}x${image.rows}) by ${image.ownerLabel}.")
                }
            } catch (error: ImageMapException) {
                player.error(error.message.orEmpty())
            }
        }, ArgumentType.Literal("delete"), playerArg, nameArg)
    }

    private fun Player.message(message: String) = sendMessage(Component.text(message, NamedTextColor.LIGHT_PURPLE))

    private fun Player.error(message: String) = sendMessage(Component.text(message, NamedTextColor.RED))
}
