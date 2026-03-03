package luna.nodes.combat.tasks

import luna.nodes.combat.objects.Projectile
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.TaskSchedule

object ProjectileTickManager {
    fun start() {
        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                // remove projectiles while iterating
                val iterator = Projectile.activeProjectiles.iterator()
                while (iterator.hasNext()) {
                    val projectile = iterator.next()
                    if (!projectile.isActive) {
                        iterator.remove()
                    } else {
                        projectile.onTick()
                    }
                }
            }.repeat(TaskSchedule.tick(1))
            .schedule()
    }
}