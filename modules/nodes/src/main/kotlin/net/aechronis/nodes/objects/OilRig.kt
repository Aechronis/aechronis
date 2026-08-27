package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.utils.ChatColor
import net.minestom.server.command.CommandSender
import net.minestom.server.item.Material

// tier -> dragon's breath (oil) produced per income period
private val OIL_RIG_TIER_INCOME: Map<Int, Map<Material, Double>> = mapOf(
    1 to mapOf(Material.DRAGON_BREATH to 32.0),
    2 to mapOf(Material.DRAGON_BREATH to 64.0),
    3 to mapOf(Material.DRAGON_BREATH to 128.0),
)

class OilRig(
    chunkX: Int,
    chunkZ: Int,
    tier: Int,
) : Building(chunkX, chunkZ, tier) {
    companion object {
        fun load(chunkX: Int, chunkZ: Int, tier: Int): OilRig = OilRig(chunkX, chunkZ, tier).also { Building.register(it) }

        fun create(chunkX: Int, chunkZ: Int, tier: Int): Result<OilRig> {
            if (Building.hasAt(chunkX, chunkZ)) return Result.failure(net.aechronis.nodes.constants.ErrorChunkHasBuilding)
            return OilRig(chunkX, chunkZ, tier).also {
                Building.register(it)
                Nodes.needsSave = true
            }.let(Result.Companion::success)
        }
    }

    override val type: String = "oil_rig"
    override val minimapIconCodepoint: Int = 0xE00D

    override fun income(): Map<Material, Double> = OIL_RIG_TIER_INCOME.getValue(tier)

    override fun createSaveState(): OilRigSaveState = OilRigSaveState(this)

    class OilRigSaveState(oilRig: OilRig) : BuildingSaveState() {
        override val type = oilRig.type
        val chunkX = oilRig.chunkX
        val chunkZ = oilRig.chunkZ
        val tier = oilRig.tier

        override fun createJsonString(): String = "{" +
            "\"type\":\"$type\"," +
            "\"chunkX\":$chunkX," +
            "\"chunkZ\":$chunkZ," +
            "\"tier\":$tier" +
            "}"
    }

    override fun printInfo(sender: CommandSender) {
        Message.print(sender, "${ChatColor.AQUA}${ChatColor.BOLD}Oil rig:")
        Message.print(sender, "${ChatColor.AQUA}- Chunk: ($chunkX, $chunkZ)")
        Message.print(sender, "${ChatColor.AQUA}- Tier: $tier")
        Message.print(sender, "${ChatColor.AQUA}- Produces ${income().getValue(Material.DRAGON_BREATH).toInt()} oil per hour")
    }
}
