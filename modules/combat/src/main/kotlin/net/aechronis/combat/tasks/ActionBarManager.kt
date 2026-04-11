package net.aechronis.combat.tasks

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.timer.TaskSchedule

object ActionBarManager {
    // run scheduler updating action bar
    fun start() {
        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                    updateActionBar(player)
                }
            }.repeat(TaskSchedule.tick(1))
            .schedule()
    }

    fun updateActionBar(player: Player) {
        val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun ?: return
        // set correct text
        if (Combat.reloadTasks[player] == null) {
            player.sendActionBar(gun.ammoText(player))
        }
    }
}
