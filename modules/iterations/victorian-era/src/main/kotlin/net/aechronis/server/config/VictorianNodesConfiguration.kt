package net.aechronis.server.config

import net.aechronis.nodes.NodesConfig
import net.aechronis.nodes.objects.OreDeposit
import net.aechronis.nodes.objects.TerritoryResources
import net.minestom.server.coordinate.Pos
import net.minestom.server.item.Material

/** Kept separate so unrelated item changes can retain the running Nodes world and configuration. */
internal object VictorianNodesConfiguration {
    fun create(spawnPoint: Pos): NodesConfig =
        NodesConfig(
            defaultRespawnPoint = spawnPoint,
            chunkAttackTime = 120_000,
            chunkAttackFromWastelandMultiplier = 1.25,
            chunkAttackHomeMultiplier = 1.25,
            globalResources =
                TerritoryResources(
                    ores =
                        mutableMapOf(
                            Material.IRON_ORE to OreDeposit(Material.IRON_ORE, 0.0405, 1, 1),
                            Material.GOLD_ORE to OreDeposit(Material.GOLD_ORE, 0.0225, 1, 1),
                            Material.DIAMOND_ORE to OreDeposit(Material.DIAMOND_ORE, 0.015, 1, 1),
                            Material.COPPER_ORE to OreDeposit(Material.COPPER_ORE, 0.0045, 1, 1),
                            Material.COAL to OreDeposit(Material.COAL, 0.055, 1, 1),
                            Material.REDSTONE to OreDeposit(Material.REDSTONE, 0.0125, 1, 1),
                            Material.BLAZE_POWDER to OreDeposit(Material.BLAZE_POWDER, 0.0125, 1, 1),
                        ),
                ),
        )
}
