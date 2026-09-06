package net.aechronis.craftingstore

import net.minestom.server.entity.Player
import java.nio.file.Path
import java.util.logging.Logger

class CraftingStoreOptions(
    val dataDirectory: Path = Path.of("craftingstore"),
    val permissionChecker: (Player, String) -> Boolean = { _, _ -> false },
    val economy: CraftingStoreEconomy? = null,
    val logger: Logger = Logger.getLogger("CraftingStore"),
    val moduleVersion: String = "aechronis-minestom",
    val registerCommand: (
        net.minestom.server.command.builder.Command,
    ) -> Unit = {
        net.minestom.server.MinecraftServer
            .getCommandManager()
            .register(it)
    },
    val unregisterCommand: (
        net.minestom.server.command.builder.Command,
    ) -> Unit = {
        net.minestom.server.MinecraftServer
            .getCommandManager()
            .unregister(it)
    },
    val upstreamVersion: String = "2.11.2",
)
