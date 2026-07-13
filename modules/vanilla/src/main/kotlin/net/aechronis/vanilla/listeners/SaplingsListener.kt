package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Saplings
import net.aechronis.vanilla.objects.BlockKey
import net.aechronis.vanilla.objects.SaplingType
import net.aechronis.vanilla.objects.SaplingsPlanted
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

object SaplingsListener {
    fun onPlace(event: PlayerBlockPlaceEvent) {
        val type = SaplingType.fromBlock(event.block) ?: return
        val instance = event.player.instance ?: return
        val pos = event.blockPosition.asVec()
        Saplings.saplings[BlockKey(instance, pos)] = SaplingsPlanted(type, System.currentTimeMillis())
    }

    fun onInteract(event: PlayerBlockInteractEvent) {
        if (event.hand != PlayerHand.MAIN) return
        val player = event.player
        if (player.itemInMainHand.material() != Material.BONE_MEAL) return
        val type = SaplingType.fromBlock(event.block) ?: return

        event.isCancelled = true
        val instance = player.instance ?: return
        val key = BlockKey(instance, event.blockPosition.asVec())
        val planted = Saplings.saplings.getOrPut(key) { SaplingsPlanted(type, System.currentTimeMillis()) }

        planted.boneMeal++
        if (planted.boneMeal >= Vanilla.config.saplingBoneMealAmount) {
            if (planted.type.giant && Saplings.tryGiant(instance, key.pos, planted.type)) {
                return
            }
            if (Saplings.grow(key, planted)) Saplings.saplings.remove(key)
        }

        if (player.gameMode == GameMode.CREATIVE) return
        val held = player.itemInMainHand
        player.itemInMainHand =
            if (held.amount() > 1) held.withAmount(held.amount() - 1) else ItemStack.AIR
    }

    fun onBreak(event: PlayerBlockBreakEvent) {
        if (SaplingType.fromBlock(event.block) == null) return
        val instance = event.player.instance ?: return
        Saplings.saplings.remove(BlockKey(instance, event.blockPosition.asVec()))
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerBlockPlaceEvent::class.java, SaplingsListener::onPlace)
        Vanilla.eventNode.addListener(PlayerBlockInteractEvent::class.java, SaplingsListener::onInteract)
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, SaplingsListener::onBreak)
    }
}
