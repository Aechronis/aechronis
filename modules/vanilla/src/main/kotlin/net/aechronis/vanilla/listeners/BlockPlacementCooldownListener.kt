package net.aechronis.vanilla.listeners

import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.network.packet.server.play.SetCooldownPacket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BlockPlacementCooldownListener {
    private const val MILLIS_PER_TICK = 50L
    private val eventNode = EventNode.all("block-placement-cooldowns").setPriority(-1_000)
    private val cooldowns = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>>()

    fun apply(
        player: Player,
        cooldownGroup: String,
        cooldownTicks: Int,
    ) {
        if (cooldownTicks <= 0) return

        val expiresAt = System.currentTimeMillis() + cooldownTicks * MILLIS_PER_TICK
        cooldowns.getOrPut(player.uuid) { ConcurrentHashMap() }[cooldownGroup] = expiresAt
        player.sendPacket(SetCooldownPacket(cooldownGroup, cooldownTicks))
    }

    fun onPlace(event: PlayerBlockPlaceEvent) {
        if (event.isCancelled) return

        val item = event.player.itemIn(event.hand)
        if (!item.material().isBlock()) return

        val cooldownGroup = item.cooldownGroup()
        val playerCooldowns = cooldowns[event.player.uuid] ?: return
        val expiresAt = playerCooldowns[cooldownGroup] ?: return
        if (expiresAt > System.currentTimeMillis()) {
            event.isCancelled = true
        } else {
            playerCooldowns.remove(cooldownGroup, expiresAt)
            if (playerCooldowns.isEmpty()) cooldowns.remove(event.player.uuid, playerCooldowns)
        }
    }

    private fun onDisconnect(event: PlayerDisconnectEvent) {
        cooldowns.remove(event.player.uuid)
    }

    private fun Player.itemIn(hand: PlayerHand): ItemStack = if (hand == PlayerHand.MAIN) itemInMainHand else itemInOffHand

    private fun ItemStack.cooldownGroup(): String = get(DataComponents.USE_COOLDOWN)?.cooldownGroup() ?: material().key().asString()

    fun init() {
        MinecraftServer.getGlobalEventHandler().addChild(eventNode)
        eventNode.addListener(PlayerBlockPlaceEvent::class.java, ::onPlace)
        eventNode.addListener(PlayerDisconnectEvent::class.java, ::onDisconnect)
    }
}
