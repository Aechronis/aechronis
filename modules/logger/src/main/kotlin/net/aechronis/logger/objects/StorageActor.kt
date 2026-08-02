package net.aechronis.logger.objects

import net.minestom.server.entity.Player

data class StorageActor(
    val player: Player,
    val generation: Long,
)
