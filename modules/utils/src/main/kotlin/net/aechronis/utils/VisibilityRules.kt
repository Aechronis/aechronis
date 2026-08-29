package net.aechronis.utils

import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.play.PlayerInfoRemovePacket
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket
import java.util.EnumSet
import java.util.UUID

object VisibilityRules {
    private val rules = mutableMapOf<UUID, MutableMap<String, (Player) -> Boolean>>()

    fun set(
        player: Player,
        owner: String,
        rule: (Player) -> Boolean,
    ) {
        val viewRule =
            synchronized(rules) {
                rules.getOrPut(player.uuid, ::mutableMapOf)[owner] = rule
                combinedRule(player.uuid)
            }
        apply(player, viewRule)
    }

    fun remove(
        player: Player,
        owner: String,
    ) {
        val viewRule =
            synchronized(rules) {
                val playerRules = rules[player.uuid] ?: return
                if (playerRules.remove(owner) == null) return
                if (playerRules.isEmpty()) rules.remove(player.uuid)
                combinedRule(player.uuid)
            }
        apply(player, viewRule)
    }

    fun refresh(player: Player) {
        val viewRule = synchronized(rules) { combinedRule(player.uuid) }
        apply(player, viewRule)
    }

    fun clear(player: Player) {
        val removed = synchronized(rules) { rules.remove(player.uuid) != null }
        if (removed) apply(player, null)
    }

    /** Removes every module-owned predicate and restores normal visibility for online players. */
    fun clearAll() {
        val affected =
            synchronized(rules) {
                rules.keys.toSet().also { rules.clear() }
            }
        MinecraftServer
            .getConnectionManager()
            .onlinePlayers
            .filter { it.uuid in affected }
            .forEach { apply(it, null) }
    }

    private fun combinedRule(uuid: UUID): ((Player) -> Boolean)? {
        val playerRules = rules[uuid]?.values?.toList().orEmpty()
        return playerRules.takeIf { it.isNotEmpty() }?.let { predicates ->
            { viewer -> predicates.all { predicate -> predicate(viewer) } }
        }
    }

    private fun apply(
        player: Player,
        viewRule: ((Player) -> Boolean)?,
    ) = applyForViewers(player, viewRule, MinecraftServer.getConnectionManager().onlinePlayers.filter { it != player })

    /** Visible for tests so the client packet order can be verified without a socket connection. */
    internal fun applyForViewers(
        player: Player,
        viewRule: ((Player) -> Boolean)?,
        viewers: Collection<Player>,
    ) {
        viewers.filter { viewRule?.invoke(it) != false }.forEach { viewer ->
            viewer.sendPacket(playerInfoPacket(player))
        }
        player.updateViewableRule(viewRule)
        viewers.filter { viewRule?.invoke(it) == false }.forEach { viewer ->
            viewer.sendPacket(PlayerInfoRemovePacket(player.uuid))
        }
    }

    private fun playerInfoPacket(player: Player): PlayerInfoUpdatePacket {
        val properties =
            player.skin?.let { skin ->
                listOf(PlayerInfoUpdatePacket.Property("textures", skin.textures, skin.signature))
            } ?: emptyList()
        val entry =
            PlayerInfoUpdatePacket.Entry(
                player.uuid,
                player.username,
                properties,
                player.isListed,
                player.latency,
                player.gameMode,
                player.displayName,
                null,
                player.listOrder,
                player.settings.displayedSkinParts.toInt() and 0x40 != 0,
            )
        return PlayerInfoUpdatePacket(EnumSet.allOf(PlayerInfoUpdatePacket.Action::class.java), entry)
    }
}
