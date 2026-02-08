package luna.nodes.combat.listeners

import luna.nodes.combat.Combat
import luna.nodes.combat.objects.Gun
import luna.nodes.combat.objects.Item
import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.PlayerHandAnimationEvent
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.timer.TaskSchedule

object FireListener {
    private fun onPlayerUseItem(event: PlayerUseItemEvent) {
        val player = event.player
        val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun ?: return
        if (!gun.automatic) {
            return
        }
        Combat.playerFiring[player] = true
        Combat.firingResetTasks[player]?.cancel()

        // event is triggered every 4 ticks, schedule task in 6 ticks and cancel it if event is triggered again
        Combat.firingResetTasks[player] =
            MinecraftServer
                .getSchedulerManager()
                .buildTask {
                    Combat.playerFiring[player] = false
                }.delay(TaskSchedule.tick(6))
                .schedule()
    }

    private fun onPlayerHandAnimation(event: PlayerHandAnimationEvent) {
        val player = event.player
        val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun ?: return

        if (!gun.automatic) gun.fire(player)
    }

    fun init() {
        Combat.eventNode.addListener(PlayerUseItemEvent::class.java, FireListener::onPlayerUseItem)
        Combat.eventNode.addListener(PlayerHandAnimationEvent::class.java, FireListener::onPlayerHandAnimation)
    }
}
