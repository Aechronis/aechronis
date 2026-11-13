package net.aechronis.luckperms

import net.luckperms.api.event.EventBus
import net.luckperms.api.event.EventSubscription
import net.luckperms.api.event.context.ContextUpdateEvent
import net.luckperms.api.event.user.UserDataRecalculateEvent
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.tag.Tag
import java.util.UUID

/**
 * Resends a player's command list on their own tick when their permissions or contexts change.
 *
 * Replaces upstream's updater, which builds the list on a new LuckPerms thread while the tick
 * runs, testing every command's condition against the player. On 2026-11-13 two of those
 * threads spun at full CPU after web editor changes to online players, until the server hung.
 */
internal class CommandListUpdater(
    eventBus: EventBus,
) : AutoCloseable {
    private val subscriptions: List<EventSubscription<*>> =
        listOf(
            eventBus.subscribe(UserDataRecalculateEvent::class.java) { request(it.user.uniqueId) },
            eventBus.subscribe(
                ContextUpdateEvent::class.java,
            ) { event -> event.getSubject(Player::class.java).ifPresent { request(it.uuid) } },
        )

    private fun request(uuid: UUID) {
        val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid) ?: return
        // A web editor save recalculates once per changed node; send one list for the whole burst.
        if (player.getAndSetTag(PENDING, true) == true) return
        player.scheduleNextTick {
            player.removeTag(PENDING)
            player.refreshCommands()
        }
    }

    override fun close() {
        subscriptions.forEach(EventSubscription<*>::close)
    }

    private companion object {
        // Transient, so a refresh still queued when the player leaves goes with their Player.
        val PENDING: Tag<Boolean> = Tag.Transient("aechronis:luckperms-command-list-pending")
    }
}
