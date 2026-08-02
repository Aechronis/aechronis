package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.PlayerInputEvent
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.timer.TaskSchedule
import kotlin.collections.set

object AimingListener {
    private fun onPlayerUseItem(event: PlayerUseItemEvent) {
        val player = event.player
        val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun ?: return
        if (gun.automatic) return
        Combat.playerAiming[player] = true
        Combat.aimingResetTasks[player]?.cancel()
        if (player.isSneaking) return // sneaking already handling aiming, dont override

        // event is triggered every 4 ticks, schedule task in 6 ticks and cancel it if event is triggered again
        Combat.aimingResetTasks[player] =
            MinecraftServer
                .getSchedulerManager()
                .buildTask {
                    Combat.playerAiming[player] = false
                }.delay(TaskSchedule.tick(6))
                .schedule()
    }

    private fun onPlayerInput(event: PlayerInputEvent) {
        Combat.playerAiming[event.player] = event.isHoldingShiftKey
    }

    fun init() {
        Combat.eventNode.addListener(PlayerUseItemEvent::class.java, AimingListener::onPlayerUseItem)
        Combat.eventNode.addListener(PlayerInputEvent::class.java, AimingListener::onPlayerInput)
    }
}
