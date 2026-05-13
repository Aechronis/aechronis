package net.aechronis.vanilla

data class VanillaConfig(
    val path: String = "vanilla",
    val playerDataPath: String = "playerdata",
    val cropGrowthCheckSeconds: Long = 20L,
    val wheatMsPerStage: Long = 72_000L,
    val carrotMsPerStage: Long = 72_000L,
    val potatoMsPerStage: Long = 72_000L,
)
