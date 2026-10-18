package net.aechronis.duels

import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.vanilla.managers.Combat
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Invites {
    private const val EXPIRY_SECONDS = 30L

    private class Invite(
        val from: UUID,
        val to: UUID,
        val kit: Kit,
        val map: MapDef,
    ) {
        lateinit var expiry: Task
    }

    // One pending invite per target; a newer one replaces it.
    private val byTarget = ConcurrentHashMap<UUID, Invite>()

    private fun error(
        player: Player,
        text: String,
    ) = player.sendMessage(Component.text(text, NamedTextColor.RED))

    private fun player(uuid: UUID) = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid)

    /** Reasons a duel cannot involve [p] right now, or null. */
    private fun blocker(p: Player): String? =
        when {
            MatchManager.inMatch(p) -> "${p.username} is already in a match."
            SnapshotStore.exists(p.uuid) -> "${p.username} is still being restored."
            Combat.isInCombat(p) -> "${p.username} is in combat."
            else -> null
        }

    fun send(
        from: Player,
        to: Player,
        kit: Kit,
        map: MapDef?,
    ) {
        if (map == null) return error(from, "No duel maps are available.")
        if (from === to) return error(from, "You cannot duel yourself.")
        if (MatchManager.isFull()) return error(from, "All duel arenas are busy, try again shortly.")
        (blocker(from) ?: blocker(to))?.let { return error(from, it) }
        byTarget.values.removeIf { it.from == from.uuid && it.expiry.cancel().let { true } }
        val invite = Invite(from.uuid, to.uuid, kit, map)
        byTarget.put(to.uuid, invite)?.expiry?.cancel()
        invite.expiry =
            ModuleScheduler
                .buildTask {
                    if (byTarget.remove(to.uuid, invite)) {
                        player(from.uuid)?.sendMessage(Component.text("Your duel invite to ${to.username} expired.", NamedTextColor.GRAY))
                    }
                }.delay(TaskSchedule.seconds(EXPIRY_SECONDS))
                .schedule()
        from.sendMessage(
            Component.text("Duel invite sent to ${to.username} (${kit.displayName}, ${map.name}).", NamedTextColor.LIGHT_PURPLE),
        )
        to.sendMessage(
            Component
                .text("${from.username} challenged you to a duel: ${kit.displayName} on ${map.name}. ", NamedTextColor.LIGHT_PURPLE)
                .append(
                    Component
                        .text("[Accept]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/duel accept ${from.username}")),
                ).append(Component.text(" "))
                .append(
                    Component
                        .text("[Deny]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/duel deny ${from.username}")),
                ),
        )
    }

    fun accept(
        target: Player,
        fromName: String?,
    ) {
        val invite = take(target, fromName) ?: return error(target, "You have no pending duel invite.")
        val challenger = player(invite.from) ?: return error(target, "The challenger is no longer online.")
        if (MatchManager.isFull()) {
            error(target, "All duel arenas are busy, try again shortly.")
            return error(challenger, "All duel arenas are busy, try again shortly.")
        }
        (blocker(challenger) ?: blocker(target))?.let {
            error(target, it)
            return error(challenger, it)
        }
        MatchManager.start(DuelMode, invite.kit, invite.map, listOf(listOf(challenger), listOf(target)))
    }

    fun deny(
        target: Player,
        fromName: String?,
    ) {
        val invite = take(target, fromName) ?: return error(target, "You have no pending duel invite.")
        target.sendMessage(Component.text("Duel invite denied.", NamedTextColor.LIGHT_PURPLE))
        player(invite.from)?.sendMessage(Component.text("${target.username} denied your duel invite.", NamedTextColor.GRAY))
    }

    private fun take(
        target: Player,
        fromName: String?,
    ): Invite? {
        val invite = byTarget[target.uuid] ?: return null
        if (fromName != null && player(invite.from)?.username?.equals(fromName, ignoreCase = true) != true) return null
        if (!byTarget.remove(target.uuid, invite)) return null
        invite.expiry.cancel()
        return invite
    }

    fun clear() {
        byTarget.values.forEach { it.expiry.cancel() }
        byTarget.clear()
    }
}
