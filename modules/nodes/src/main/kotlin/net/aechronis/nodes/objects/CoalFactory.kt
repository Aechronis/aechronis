package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.utils.ChatColor
import net.kyori.adventure.text.Component
import net.minestom.server.command.CommandSender
import net.minestom.server.item.Material

// tier -> coal produced per income period
private val COAL_FACTORY_TIER_INCOME: Map<Int, Map<Material, Double>> = mapOf(
    1 to mapOf(Material.COAL to 100.0),
    2 to mapOf(Material.COAL to 200.0),
    3 to mapOf(Material.COAL to 300.0),
)

class CoalFactory(
    chunkX: Int,
    chunkZ: Int,
    tier: Int,
) : Building(chunkX, chunkZ, tier, Component.text("Coal Factory")) {
    companion object {
        fun load(chunkX: Int, chunkZ: Int, tier: Int): CoalFactory = CoalFactory(chunkX, chunkZ, tier).also { Building.register(it) }

        fun create(chunkX: Int, chunkZ: Int, tier: Int): Result<CoalFactory> {
            if (Building.hasAt(chunkX, chunkZ)) return Result.failure(net.aechronis.nodes.constants.ErrorChunkHasBuilding)
            return CoalFactory(chunkX, chunkZ, tier).also {
                Building.register(it)
                Nodes.markWorldDirty()
            }.let(Result.Companion::success)
        }
    }

    override val type: String = "coal_factory"
    override val minimapIconCodepoint: Int = 0xE00D

    override fun income(): Map<Material, Double> = COAL_FACTORY_TIER_INCOME.getValue(tier)

    override fun createSaveState(): CoalFactorySaveState = CoalFactorySaveState(this)

    class CoalFactorySaveState(coalFactory: CoalFactory) : BuildingSaveState() {
        override val type = coalFactory.type
        override val chunkX = coalFactory.chunkX
        override val chunkZ = coalFactory.chunkZ
        override val tier = coalFactory.tier
    }

    override fun printInfo(sender: CommandSender) {
        Message.print(sender, "${ChatColor.AQUA}${ChatColor.BOLD}Coal factory:")
        Message.print(sender, "${ChatColor.AQUA}- Chunk: ($chunkX, $chunkZ)")
        Message.print(sender, "${ChatColor.AQUA}- Tier: $tier")
        Message.print(sender, "${ChatColor.AQUA}- Produces ${income().getValue(Material.COAL).toInt()} coal per hour")
    }
}
