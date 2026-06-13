package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.registry.RegistryKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object FallDamageListener {
    private val FALL: RegistryKey<DamageType> = RegistryKey.unsafeOf("minecraft:fall")
    private val fallStartY = ConcurrentHashMap<UUID, Double>()

    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        val gm = player.gameMode
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            fallStartY.remove(player.uuid)
            return
        }

        val newY = event.newPosition.y

        if (!event.isOnGround) {
            fallStartY.merge(player.uuid, newY, ::maxOf)
        } else {
            val startY = fallStartY.remove(player.uuid) ?: return
            val damage = (startY - newY - 3.0).toFloat()
            if (damage > 0f && player.health > 0f) {
                player.damage(FALL, damage)
            }
        }
    }

    fun onDeath(event: PlayerDeathEvent) {
        fallStartY.remove(event.player.uuid)
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        fallStartY.remove(event.player.uuid)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerMoveEvent::class.java, FallDamageListener::onMove)
        Vanilla.eventNode.addListener(PlayerDeathEvent::class.java, FallDamageListener::onDeath)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, FallDamageListener::onDisconnect)
    }
}
