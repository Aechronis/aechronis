package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.utils.ChatColor
import net.minestom.server.command.CommandSender

class TrainStationBuilding(
    chunkX: Int,
    chunkZ: Int,
    tier: Int,
) : Building(chunkX, chunkZ, tier) {
    companion object {
        fun load(chunkX: Int, chunkZ: Int, tier: Int): TrainStationBuilding = TrainStationBuilding(chunkX, chunkZ, tier).also { Building.register(it) }

        fun create(chunkX: Int, chunkZ: Int, tier: Int): Result<TrainStationBuilding> {
            if (Building.hasAt(chunkX, chunkZ)) return Result.failure(net.aechronis.nodes.constants.ErrorChunkHasBuilding)
            return TrainStationBuilding(chunkX, chunkZ, tier).also {
                Building.register(it)
                Nodes.markWorldDirty()
            }.let(Result.Companion::success)
        }
    }

    override val type: String = "train"
    override val minimapIconCodepoint: Int = 0xE00C

    override fun createSaveState(): TrainStationBuildingSaveState = TrainStationBuildingSaveState(this)

    class TrainStationBuildingSaveState(train: TrainStationBuilding) : BuildingSaveState() {
        override val type = train.type
        override val chunkX = train.chunkX
        override val chunkZ = train.chunkZ
        override val tier = train.tier
    }

    override fun printInfo(sender: CommandSender) {
        Message.print(sender, "${ChatColor.AQUA}${ChatColor.BOLD}Train station:")
        Message.print(sender, "${ChatColor.AQUA}- Chunk: ($chunkX, $chunkZ)")
        Message.print(sender, "${ChatColor.AQUA}- Tier: $tier")
        Message.print(sender, "${ChatColor.AQUA}- Boosts every gold-block train station in this chunk")
    }
}
