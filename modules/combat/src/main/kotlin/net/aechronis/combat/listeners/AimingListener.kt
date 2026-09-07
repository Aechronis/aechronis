package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent
import net.minestom.server.event.player.PlayerInputEvent
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent
import net.minestom.server.timer.TaskSchedule
import kotlin.collections.set

object AimingListener {
    private fun onPlayerUseItem(event: PlayerUseItemEvent) {
        val player = event.player
        // crossbow material is only for its pose; don't start vanilla charging
        if (Item.getFromItemStack(event.itemStack) is Gun) event.itemUseTime = 0
        if (event.hand != PlayerHand.MAIN || Item.getFromItemStack(player.itemInMainHand) !is Gun) return
        refreshRightClickAim(player)
    }

    private fun onPlayerUseItemOnBlock(event: PlayerUseItemOnBlockEvent) {
        if (event.hand != PlayerHand.MAIN || Item.getFromItemStack(event.player.itemInMainHand) !is Gun) return
        refreshRightClickAim(event.player)
    }

    private fun refreshRightClickAim(player: Player) {
        Combat.playerAiming[player] = true
        Combat.aimingResetTasks.remove(player)?.cancel()

        // right click repeats every four ticks, including during automatic fire
        Combat.aimingResetTasks[player] =
            ModuleScheduler
                .buildTask {
                    Combat.aimingResetTasks.remove(player)
                    Combat.playerAiming[player] = player.isSneaking
                }.delay(TaskSchedule.tick(6))
                .schedule()
    }

    private fun onPlayerInput(event: PlayerInputEvent) {
        Combat.playerAiming[event.player] = event.isHoldingShiftKey || Combat.aimingResetTasks.containsKey(event.player)
    }

    private fun onPlayerChangeHeldSlot(event: PlayerChangeHeldSlotEvent) {
        if (event.isCancelled || event.oldSlot == event.newSlot) return
        Combat.aimingResetTasks.remove(event.player)?.cancel()
        Combat.playerAiming[event.player] = event.player.isSneaking
    }

    fun init() {
        Combat.eventNode.addListener(PlayerUseItemEvent::class.java, AimingListener::onPlayerUseItem)
        Combat.eventNode.addListener(PlayerUseItemOnBlockEvent::class.java, AimingListener::onPlayerUseItemOnBlock)
        Combat.eventNode.addListener(PlayerInputEvent::class.java, AimingListener::onPlayerInput)
        Combat.eventNode.addListener(PlayerChangeHeldSlotEvent::class.java, AimingListener::onPlayerChangeHeldSlot)
    }
}
