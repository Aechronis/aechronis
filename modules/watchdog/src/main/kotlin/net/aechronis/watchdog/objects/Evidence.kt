package net.aechronis.watchdog.objects

internal data class Evidence(
    val certainty: Double,
    val details: String,
    val timestampMillis: Long,
)
