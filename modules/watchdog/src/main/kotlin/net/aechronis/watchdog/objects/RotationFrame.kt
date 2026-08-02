package net.aechronis.watchdog.objects

data class RotationFrame(
    val timestampNanos: Long,
    val yaw: Float,
    val pitch: Float,
)
