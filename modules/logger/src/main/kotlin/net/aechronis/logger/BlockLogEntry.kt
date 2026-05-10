package net.aechronis.logger

import java.util.UUID

data class BlockLogEntry(
    val timestamp: Long,
    val playerUuid: UUID,
    val playerName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val blockOld: String,
    val blockNew: String,
    val action: BlockAction,
)
