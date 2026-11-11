package net.aechronis.treefeller

// Detection defaults match Thizzy'z Tree Feller's config.yml, except maxLogs, minVerticalRatio and the
// leaf limits, which are loosened for the map's oversized custom trees (hundreds of logs, wide branches).
data class TreeFellerConfig(
    val enabled: Boolean = true,
    // Logs every felling decision (cut position, tree size or the reason it was not felled).
    val debug: Boolean = false,
    // How far (blocks, horizontally) and how many touching same-species logs to scan to split trees apart.
    val scanRadius: Int = 48,
    val maxClusterLogs: Int = 10000,
    val maxLogs: Int = 1500,
    // 1 so small bushes (a log or two inside a leaf ball) fell too; the leaf requirement still applies.
    val requiredLogs: Int = 1,
    val requiredLeaves: Int = 10,
    val leafRange: Int = 12,
    val diagonalLeaves: Boolean = false,
    val maxHorizontalRun: Int = 6,
    val minVerticalRatio: Double = 0.4,
    val maxCutHeight: Int = 5,
    val breakLeaves: Boolean = true,
    val maxLeaves: Int = 8000,
    val blocksPerTick: Int = 24,
    // One in this many felled leaves shows break particles and sound (logs always do).
    val leafEffectEvery: Int = 12,
    val tickInterval: Int = 1,
    val saplingChance: Double = 0.05,
    val stickChance: Double = 0.02,
)
