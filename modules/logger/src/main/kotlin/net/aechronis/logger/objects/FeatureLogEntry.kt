package net.aechronis.logger.objects

import java.util.UUID

data class FeatureLogEntry(
    val timestamp: Long,
    val playerUuid: UUID?,
    val playerName: String?,
    val source: String,
    val action: String,
    val summary: String,
    val x: Int? = null,
    val y: Int? = null,
    val z: Int? = null,
    val data: Map<String, String> = emptyMap(),
    val origin: String = LogMetadata.LOGGER,
)
