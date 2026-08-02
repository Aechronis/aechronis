package net.aechronis.vanilla.config

import net.aechronis.vanilla.objects.KothConfig
import java.time.LocalTime

data class KothsConfig(
    val kothLengthSeconds: Long = 3600L,
    val kothTimes: Map<String, List<LocalTime>> = emptyMap(),
    val koths: List<KothConfig> = emptyList(),
    val kothDisplayRadiusBlocks: Double = 250.0,
)
