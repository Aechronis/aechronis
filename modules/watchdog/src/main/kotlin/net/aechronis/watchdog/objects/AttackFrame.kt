package net.aechronis.watchdog.objects

import net.minestom.server.coordinate.Pos
import java.util.UUID

data class AttackFrame(
    val timestampMillis: Long,
    val targetId: UUID,
    val attackerPosition: Pos,
    val targetPosition: Pos,
    val targetWidth: Double,
    val targetHeight: Double,
    val yaw: Float,
    val pitch: Float,
    val confirmed: Boolean,
)
