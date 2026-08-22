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
    val upstreamVersion: String = "2.11.2",
)
