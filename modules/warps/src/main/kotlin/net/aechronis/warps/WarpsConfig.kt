package net.aechronis.warps

data class WarpsConfig(
    val path: String = "warps",
    val warpsPath: String = "warps.json",
    val warpCooldownSeconds: Long = 15L,
)
