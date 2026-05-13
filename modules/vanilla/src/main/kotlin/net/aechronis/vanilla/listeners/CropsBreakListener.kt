package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Crops
import net.aechronis.vanilla.objects.CropKey
import net.aechronis.vanilla.objects.CropType
import net.aechronis.vanilla.utils.PlayerAddons.giveDrops
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.instance.block.Block

object CropsBreakListener {
    fun onBreak(event: PlayerBlockBreakEvent) {
        val block = event.block
        val pos = event.blockPosition
        val instance = event.player.instance ?: return

        // crop broken
        val cropType = CropType.fromBlock(block)
        if (cropType != null) {
            event.isCancelled = true
            val age = block.getProperty("age")?.toIntOrNull() ?: 0
            Crops.crops.remove(CropKey(instance, pos.asVec()))
            instance.setBlock(pos, Block.AIR)
            val drops = CropType.drops(cropType, age)
            event.player.giveDrops(drops)
            return
        }
        // crop block broken
        if (block.compare(Block.FARMLAND)) {
            val abovePos = pos.add(0, 1, 0) // or Vec(x, y+1, z)
            val aboveBlock = instance.getBlock(abovePos)
            val aboveCrop = CropType.fromBlock(aboveBlock) ?: return
            val age = aboveBlock.getProperty("age")?.toIntOrNull() ?: 0
            Crops.crops.remove(CropKey(instance, abovePos.asVec()))
            instance.setBlock(abovePos, Block.AIR)
            event.player.giveDrops(CropType.drops(aboveCrop, age))
            return
        }
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, CropsBreakListener::onBreak)
    }
}
