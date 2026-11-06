package net.aechronis.combat.tasks

import net.aechronis.combat.objects.Projectile
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.MinecraftServer
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
                        try {
                            projectile.onTick()
                        } catch (error: Throwable) {
                            if (error is VirtualMachineError || error is ThreadDeath) throw error
                            // Minestom cancels repeating tasks when a callback throws. Isolate
                            // failed projectiles, including linkage errors in block rules.
                            iterator.remove()
                            try {
                                projectile.discard()
                            } catch (cleanupError: Throwable) {
                                if (cleanupError is VirtualMachineError || cleanupError is ThreadDeath) throw cleanupError
                                if (cleanupError !== error) error.addSuppressed(cleanupError)
                            }
                            MinecraftServer.getExceptionManager().handleException(
                                RuntimeException(
                                    "[Combat] Projectile update failed for ${projectile.model} in ${projectile.instance.uuid}",
                                    error,
                                ),
                            )
                        }
                    }
                }
            }.repeat(TaskSchedule.tick(1))
            .schedule()
    }
}
