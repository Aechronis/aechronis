package net.aechronis.imagemaps

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.entity.Player

class ImageCommand : Command("image", ImageMaps.USE_PERMISSION) {
    private val nameArg =
        ArgumentType.Word("name").setSuggestionCallback { sender, _, suggestion ->
            val player = sender as? Player ?: return@setSuggestionCallback
            val prefix = suggestion.input.substringAfterLast(' ')
            ImageMaps
                .list(player.uuid)
                .map(ImageMaps.Image::name)
                .filter { it.startsWith(prefix, ignoreCase = true) }
                .forEach { suggestion.addEntry(SuggestionEntry(it)) }
        }

    init {
        setDefaultExecutor { player: Player, _ ->
            player.message(
                "Upload with /imageupload in Discord. Browse everyone's images with /images. Yours: /image list, /image give <name>, /image delete <name>.",
            )
        }

        addSyntax({ player: Player, _ ->
            val images = ImageMaps.list(player.uuid)
            player.message(
                if (images.isEmpty()) {
                    "You have no images."
                } else {
                    images.joinToString(prefix = "Images: ") { "${it.name} (${it.cols}x${it.rows})" }
                },
            )
        }, ArgumentType.Literal("list"))

        addSyntax({ player: Player, context ->
            val image = ImageMaps.find(player.uuid, context[nameArg]) ?: return@addSyntax player.error("Unknown image: ${context[nameArg]}")
            val missed = ImageMaps.items(image).count { !player.inventory.addItemStack(it) }
            ImageMaps.sendInventory(player)
            if (missed > 0) player.error("Inventory full: $missed tile(s) were not given. Free some slots and run it again.")
            player.message("Gave ${image.name}: ${image.cols}x${image.rows} tiles. Place them row by row, left to right.")
        }, ArgumentType.Literal("give"), nameArg)

        addSyntax({ player: Player, context ->
            val name = context[nameArg]
            if (ImageMaps.delete(player.uuid, name)) player.message("Deleted $name.") else player.error("Unknown image: $name")
        }, ArgumentType.Literal("delete"), nameArg)
    }

    private fun Player.message(message: String) = sendMessage(Component.text(message, NamedTextColor.LIGHT_PURPLE))

    private fun Player.error(message: String) = sendMessage(Component.text(message, NamedTextColor.RED))
}
