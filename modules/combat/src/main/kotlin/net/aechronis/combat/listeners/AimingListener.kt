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
        val state = Combat.playerStates.getOrCreate(player)
        state.aiming = true
        state.cancelAimingReset()

        // Right click repeats every four ticks, but the repeats arrive with the connection's jitter. Track the
        // largest recent gap between them (player.latency is too stale to size this) and wait that long plus a margin.
        val now = System.nanoTime()
        val gap = ((now - state.lastAimUseNanos) / 50_000_000L).toInt()
        // The block and air use events of one click can land on the same tick; they are not a gap.
        if (gap >= 1) {
            state.aimUseGapTicks = if (gap <= 20) maxOf(gap, state.aimUseGapTicks - 1) else 4
            state.lastAimUseNanos = now
        }
        state.aimingResetTask =
            ModuleScheduler
                .buildTask {
                    Combat.playerStates[player]?.aimingResetTask = null
                    Combat.playerStates.getOrCreate(player).aiming = player.isSneaking
                }.delay(TaskSchedule.tick((state.aimUseGapTicks + 3).coerceIn(6, 20)))
                .schedule()
    }

    private fun onPlayerInput(event: PlayerInputEvent) {
        val state = Combat.playerStates.getOrCreate(event.player)
        state.aiming = event.isHoldingShiftKey || state.aimingResetTask != null
    }

    private fun onPlayerChangeHeldSlot(event: PlayerChangeHeldSlotEvent) {
        if (event.isCancelled || event.oldSlot == event.newSlot) return
        val state = Combat.playerStates.getOrCreate(event.player)
        state.cancelAimingReset()
        state.aiming = event.player.isSneaking
    }

    fun init() {
        Combat.eventNode.addListener(PlayerUseItemEvent::class.java, AimingListener::onPlayerUseItem)
        Combat.eventNode.addListener(PlayerUseItemOnBlockEvent::class.java, AimingListener::onPlayerUseItemOnBlock)
        Combat.eventNode.addListener(PlayerInputEvent::class.java, AimingListener::onPlayerInput)
        Combat.eventNode.addListener(PlayerChangeHeldSlotEvent::class.java, AimingListener::onPlayerChangeHeldSlot)
    }
}
