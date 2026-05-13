package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Crops
import net.aechronis.vanilla.objects.BlockKey
import net.aechronis.vanilla.objects.CropType
import net.aechronis.vanilla.objects.CropsPlantedCrop
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack

object CropsPlantListener {
    fun onInteract(event: PlayerBlockInteractEvent) {
        if (event.hand != PlayerHand.MAIN) return
        val player = event.player
        val cropType = CropType.fromSeed(player.itemInMainHand.material()) ?: return
        val block = event.block
        event.isCancelled = true

        val instance = player.instance ?: return
        val cropPos: BlockVec = event.blockPosition.add(0, 1, 0)
        if (!instance.getBlock(cropPos).isAir) return
        if (!block.compare(Block.FARMLAND)) return

        instance.setBlock(cropPos, cropType.cropBlock.withProperty("age", "0"))
        Crops.crops[BlockKey(instance, cropPos.asVec())] = CropsPlantedCrop(cropType, System.currentTimeMillis(), 0)

        if (player.gameMode == GameMode.CREATIVE) return

        val held = player.itemInMainHand
        player.itemInMainHand =
            if (held.amount() > 1) held.withAmount(held.amount() - 1) else ItemStack.AIR
    }

    fun onPlace(event: PlayerBlockPlaceEvent) {
        val placed = event.block
        val isCrop = CropType.ALL.any { placed.compare(it.cropBlock) }
        if (!isCrop) return

        val instance =
            event.player.instance ?: run {
                event.isCancelled = true
                return
            }
        val belowPos = event.blockPosition.sub(0, 1, 0)
        if (!instance.getBlock(belowPos).compare(Block.FARMLAND)) {
            event.isCancelled = true
        }
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerBlockInteractEvent::class.java, CropsPlantListener::onInteract)
        Vanilla.eventNode.addListener(PlayerBlockPlaceEvent::class.java, CropsPlantListener::onPlace)
    }
}
