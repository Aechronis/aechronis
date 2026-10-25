package net.aechronis.vanilla.managers

import net.aechronis.combat.managers.CombatTags
import net.aechronis.combat.objects.Vehicle
import net.aechronis.server.Server
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.timer.TaskSchedule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Delayed /spawn: cancelled by movement, combat or any change of world while waiting. */
object SpawnTeleport {
    private data class Pending(
        val origin: Instance?,
    )

    private val pending = ConcurrentHashMap<UUID, Pending>()

    fun start(player: Player) {
        if (CombatTags.isInCombat(player)) return player.error("You cannot use /spawn while in combat.")
        val delay = Vanilla.config.spawnDelaySeconds.coerceAtLeast(0)
        val entry = Pending(player.instance)
        if (pending.putIfAbsent(player.uuid, entry) != null) return player.error("You are already waiting to teleport.")
        player.info("Teleporting to spawn in ${delay}s. Don't move.")
        ModuleScheduler
            .buildTask {
                if (!pending.remove(player.uuid, entry) || !player.isOnline) return@buildTask
                if (player.instance !== entry.origin) return@buildTask player.error("Spawn teleport cancelled.")
                if (CombatTags.isInCombat(player)) return@buildTask player.error("Spawn teleport cancelled: you are in combat.")
                teleport(player)
            }.delay(TaskSchedule.seconds(delay))
            .schedule()
    }

    fun cancelPending(player: Player): Boolean = pending.remove(player.uuid) != null

    fun shutdown() = pending.clear()

    private fun teleport(player: Player) {
        Vehicle.exit(player)
        if (player.instance ===
            Server.instance
        ) {
            player.teleport(Server.spawnPoint)
        } else {
            player.setInstance(Server.instance, Server.spawnPoint)
        }
        player.info("Teleported to spawn.")
    }

    private fun Player.info(text: String) = sendMessage(Component.text(text, NamedTextColor.LIGHT_PURPLE))

    private fun Player.error(text: String) = sendMessage(Component.text(text, NamedTextColor.RED))
}
