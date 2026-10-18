package net.aechronis.duels

import net.aechronis.server.hasPermission
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityDamageEvent
import net.minestom.server.event.item.ItemDropEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.event.player.PlayerCommandEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.timer.TaskSchedule

/** Arena rules: no building, no drops, frozen countdown, lethal damage becomes elimination. */
object MatchListener {
    const val BYPASS_PERMISSION = "duels.bypass"

    fun register(context: ModuleContext) {
        context.addListener(PlayerBlockBreakEvent::class.java) { if (MatchManager.inMatch(it.player)) it.isCancelled = true }
        context.addListener(PlayerBlockPlaceEvent::class.java) { if (MatchManager.inMatch(it.player)) it.isCancelled = true }
        context.addListener(PlayerBlockInteractEvent::class.java) { if (MatchManager.inMatch(it.player)) it.isCancelled = true }
        context.addListener(ItemDropEvent::class.java) { if (MatchManager.inMatch(it.player)) it.isCancelled = true }
        context.addListener(PlayerCommandEvent::class.java) {
            if (MatchManager.inMatch(it.player) && !it.player.hasPermission(BYPASS_PERMISSION)) {
                it.isCancelled = true
                it.player.sendMessage(Component.text("You cannot use commands during a match.", NamedTextColor.RED))
            }
        }
        context.addListener(PlayerMoveEvent::class.java, ::onMove)
        context.addListener(EntityDamageEvent::class.java, ::onDamage)
        context.addListener(PlayerDisconnectEvent::class.java) { MatchManager.onDisconnect(it.player) }
        context.addListener(PlayerSpawnEvent::class.java) { event ->
            // Crash or disconnect mid-match: hand the saved inventory back once vanilla has loaded its own data.
            if (!event.isFirstSpawn || !SnapshotStore.exists(event.player.uuid)) return@addListener
            val player = event.player
            ModuleScheduler
                .buildTask { if (player.isOnline && !MatchManager.inMatch(player)) MatchManager.returnToSpawn(player) }
                .delay(TaskSchedule.seconds(1))
                .schedule()
        }
    }

    private fun onMove(event: PlayerMoveEvent) {
        val match = MatchManager.matchOf(event.player) ?: return
        val to = event.newPosition
        val from = event.player.position
        when (match.state) {
            MatchState.COUNTDOWN -> if (to.x() != from.x() || to.z() != from.z()) event.isCancelled = true
            MatchState.ACTIVE -> if (to.y() < match.map.voidY) MatchManager.eliminate(match, event.player.uuid)
            MatchState.ENDING -> Unit
        }
    }

    private fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val match = MatchManager.matchOf(player) ?: return
        if (match.state != MatchState.ACTIVE) {
            event.isCancelled = true
            return
        }
        if (event.damage.amount >= player.health) {
            event.isCancelled = true
            MatchManager.eliminate(match, player.uuid)
        }
    }
}
