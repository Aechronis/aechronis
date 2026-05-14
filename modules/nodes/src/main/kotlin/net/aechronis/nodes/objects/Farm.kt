package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.utils.ChatColor
import net.minestom.server.command.CommandSender
import net.minestom.server.item.Material

// minimap glyph for farms
const val FARM_MINIMAP_TOKEN: String = "\uD83D\uDC04"

// tier -> materials produced per income period
private val FARM_TIER_INCOME: Map<Int, Map<Material, Double>> = mapOf(
    1 to mapOf(Material.WHEAT to 1.0, Material.BEEF to 1.0),
    2 to mapOf(Material.WHEAT to 2.0, Material.BEEF to 2.0, Material.CARROT to 1.0),
    3 to mapOf(Material.WHEAT to 3.0, Material.BEEF to 3.0, Material.CARROT to 2.0, Material.POTATO to 1.0),
)

class Farm(
    chunkX: Int,
    chunkZ: Int,
    tier: Int,
) : Building(chunkX, chunkZ, tier) {

    override val type: String = "farm"
    override val showOnMinimap: Boolean = true
    override val minimapToken: String = FARM_MINIMAP_TOKEN

    override fun income(): Map<Material, Double> = FARM_TIER_INCOME.getValue(tier)

    override fun createSaveState(): FarmSaveState = FarmSaveState(this)

    class FarmSaveState(f: Farm) : BuildingSaveState() {
        override val type = f.type
        val chunkX = f.chunkX
        val chunkZ = f.chunkZ
        val tier = f.tier

        override fun createJsonString(): String = "{" +
            "\"type\":\"$type\"," +
            "\"chunkX\":$chunkX," +
            "\"chunkZ\":$chunkZ," +
            "\"tier\":$tier" +
            "}"
    }

    override fun printInfo(sender: CommandSender) {
        Message.print(sender, "${ChatColor.AQUA}${ChatColor.BOLD}Farm:")
        Message.print(sender, "${ChatColor.AQUA}- Chunk: (${this.chunkX}, ${this.chunkZ})")
        Message.print(sender, "${ChatColor.AQUA}- Tier: ${this.tier}")
    }
}
