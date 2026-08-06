package net.aechronis.guard.objects

import net.aechronis.guard.flags.FlagName
import net.aechronis.guard.flags.FlagValue
import java.util.UUID

data class Zone(
    val name: String,
    val instanceId: UUID,
    val bounds: ZoneBounds,
    val priority: Int = 0,
    val flags: Map<FlagName, FlagValue> = emptyMap(),
)
