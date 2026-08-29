package net.aechronis.combat.tasks

import net.aechronis.combat.objects.Projectile
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.timer.TaskSchedule

object ProjectileTickManager {
    fun start() {
        ModuleScheduler
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
