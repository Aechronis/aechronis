package net.aechronis.treefeller

data class TreeFellerConfig(
    val enabled: Boolean = true,
    val maxSize: Int = 300,
    val maxHeight: Int = 50,
    val breakLeaves: Boolean = true,
    val leafMaxDistance: Int = 10,
    val maxLeaves: Int = 1000,
    val blocksPerTick: Int = 8,
    val tickInterval: Int = 1,
    val saplingChance: Double = 0.05,
    val stickChance: Double = 0.02,
)
