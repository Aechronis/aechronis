package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.utils.ChatColor
import net.minestom.server.command.CommandSender
import net.minestom.server.item.Material

// minimap glyph for ports
const val PORT_MINIMAP_TOKEN: String = "⚓"

// tier -> max warp distance in blocks
val PORT_TIER_WARP_DIST: Map<Int, Int> = mapOf(
    1 to 1000,
    2 to 3000,
    3 to 10000,
)

// tier -> fish produced per income period
private val PORT_TIER_INCOME: Map<Int, Map<Material, Double>> = mapOf(
    1 to mapOf(Material.COD to 1.0),
    2 to mapOf(Material.COD to 2.0, Material.SALMON to 1.0),
    3 to mapOf(Material.COD to 3.0, Material.SALMON to 2.0, Material.TROPICAL_FISH to 1.0),
)

class Port(
    val name: String,
    chunkX: Int,
    chunkZ: Int,
    tier: Int,
    val isPublic: Boolean,
) : Building(chunkX, chunkZ, tier) {

    override val type: String = "port"
    override val showOnMinimap: Boolean = true
    override val minimapToken: String = PORT_MINIMAP_TOKEN

    val maxWarpDistance: Int
        get() = PORT_TIER_WARP_DIST.getValue(tier)

    override fun income(): Map<Material, Double> = PORT_TIER_INCOME.getValue(tier)

    override fun createSaveState(): PortSaveState = PortSaveState(this)

    class PortSaveState(p: Port) : BuildingSaveState() {
        override val type = p.type
        val name = p.name
        val chunkX = p.chunkX
        val chunkZ = p.chunkZ
        val tier = p.tier
        val isPublic = p.isPublic

        override fun createJsonString(): String = "{" +
            "\"type\":\"$type\"," +
            "\"name\":\"$name\"," +
            "\"chunkX\":$chunkX," +
            "\"chunkZ\":$chunkZ," +
            "\"tier\":$tier," +
            "\"isPublic\":$isPublic" +
            "}"
    }

    override fun printInfo(sender: CommandSender) {
        Message.print(sender, "${ChatColor.AQUA}${ChatColor.BOLD}Port ${this.name}:")
        Message.print(sender, "${ChatColor.AQUA}- Chunk: (${this.chunkX}, ${this.chunkZ})")
        Message.print(sender, "${ChatColor.AQUA}- Tier: ${this.tier} ${ChatColor.GRAY}(warp range $maxWarpDistance blocks)")

        if (this.isPublic) {
            Message.print(sender, "${ChatColor.AQUA}- Public")
        } else {
            val owner = Nodes.getPortOwner(this)
            val ownerName = if (owner !== null) {
                owner.name
            } else {
                "${ChatColor.GRAY}None"
            }
            Message.print(sender, "${ChatColor.AQUA}- Owner: $ownerName")
            Message.print(sender, "${ChatColor.AQUA}- Access: Allies only")
        }
    }
}
