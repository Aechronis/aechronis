package net.aechronis.guard

import net.aechronis.guard.flags.BooleanFlagValue
import net.aechronis.guard.flags.FlagName
import net.aechronis.guard.flags.FlagValue
import net.minestom.server.entity.Player
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

data class GuardConfig(
    val dataPath: Path = Paths.get("guard", "zones.json"),
    val defaultFlags: Map<FlagName, FlagValue> = FlagName.entries.associateWith { BooleanFlagValue(true) },
    val bypass: (Player) -> Boolean = { false },
    val onDenied: (Player, FlagName) -> Unit = { _, _ -> },
    val adminPermission: String = "guard.admin",
    val bypassPermission: String = "guard.bypass",
    val loadedZoneInstanceIdMigration: ((UUID) -> UUID)? = null,
)
