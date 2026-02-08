package luna.nodes.combat.listeners

import luna.nodes.combat.Combat
import luna.nodes.combat.objects.Gun
import luna.nodes.combat.objects.Item
import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.PlayerHandAnimationEvent
import net.minestom.server.event.player.PlayerStartSneakingEvent
import net.minestom.server.event.player.PlayerStopSneakingEvent
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

    private fun onHandAnimation(event: PlayerHandAnimationEvent) {
        val player = event.player
        val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun ?: return
        if (!gun.automatic) return
        Combat.aimingResetTasks[player]?.cancel()
        if (player.isSneaking) return // sneaking already handling aiming, dont override

        Combat.playerAiming[event.player] = !(Combat.playerAiming[event.player] ?: false)
    }

    private fun onPlayerStartSneaking(event: PlayerStartSneakingEvent) {
        Combat.playerAiming[event.player] = true
    }

    private fun onPlayerStopSneaking(event: PlayerStopSneakingEvent) {
        Combat.playerAiming[event.player] = false
    }

    fun init() {
        Combat.eventNode.addListener(PlayerUseItemEvent::class.java, AimingListener::onPlayerUseItem)
        Combat.eventNode.addListener(PlayerHandAnimationEvent::class.java, AimingListener::onHandAnimation)
        Combat.eventNode.addListener(PlayerStartSneakingEvent::class.java, AimingListener::onPlayerStartSneaking)
        Combat.eventNode.addListener(PlayerStopSneakingEvent::class.java, AimingListener::onPlayerStopSneaking)
    }
}
