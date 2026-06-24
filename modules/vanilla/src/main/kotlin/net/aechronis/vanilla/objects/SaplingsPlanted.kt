package net.aechronis.vanilla.objects

data class SaplingsPlanted(
    val type: SaplingType,
    val plantedAt: Long,
    var boneMeal: Int = 0,
)
