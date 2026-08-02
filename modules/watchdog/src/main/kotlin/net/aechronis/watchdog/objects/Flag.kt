package net.aechronis.watchdog.objects

import net.aechronis.watchdog.objects.FlagType
import java.util.UUID

data class Flag(
    val playerId: UUID,
    val type: FlagType,
    val certainty: Double,
    val details: String,
    val timestampMillis: Long = System.currentTimeMillis(),
)
