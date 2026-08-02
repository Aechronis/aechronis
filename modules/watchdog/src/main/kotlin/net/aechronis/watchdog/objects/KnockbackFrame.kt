package net.aechronis.watchdog.objects

import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec

data class KnockbackFrame(
    val createdTick: Long,
    val position: Pos,
    val expectedVelocity: Vec,
    val source: String,
)
