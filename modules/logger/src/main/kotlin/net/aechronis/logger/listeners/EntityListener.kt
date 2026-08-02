package net.aechronis.logger.listeners

import net.aechronis.logger.Logger
import net.aechronis.logger.objects.FeatureLogEntry
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityDeathEvent
import java.util.UUID

object EntityListener {
    private fun onDeath(event: EntityDeathEvent) {
        val victim = event.entity as? LivingEntity ?: return
        val attacker = victim.lastDamageSource?.attacker ?: return

        val attackerIsPlayer = attacker is Player
        val victimIsPlayer = victim is Player

        val playerUuid: UUID?
        val playerName: String?
        when {
            attackerIsPlayer -> {
                playerUuid = attacker.uuid
                playerName = attacker.username
            }

            victimIsPlayer -> {
                playerUuid = victim.uuid
                playerName = victim.username
            }

            else -> {
                playerUuid = null
                playerName = null
            }
        }

        val attackerType = if (attackerIsPlayer) "player" else attacker.entityType.key().asString()
        val victimType = if (victimIsPlayer) "player" else victim.entityType.key().asString()
        val attackerLabel = if (attackerIsPlayer) attacker.username else attackerType
        val victimLabel = if (victimIsPlayer) victim.username else victimType
        val weapon =
            (attacker as? Player)
                ?.getEquipment(EquipmentSlot.MAIN_HAND)
                ?.takeIf { !it.isAir }
                ?.material()
                ?.name()

        val pos = victim.position

        Logger.log(
            FeatureLogEntry(
                timestamp = System.currentTimeMillis(),
                playerUuid = playerUuid,
                playerName = playerName,
                source = "kills",
                action = "kill",
                summary = "$attackerLabel killed $victimLabel" + (weapon?.let { " with $it" } ?: ""),
                x = pos.blockX(),
                y = pos.blockY(),
                z = pos.blockZ(),
                data =
                    mapOf(
                        "attacker_type" to attackerType,
                        "attacker_name" to attackerLabel,
                        "victim_type" to victimType,
                        "victim_name" to victimLabel,
                        "weapon" to (weapon ?: ""),
                    ),
            ),
        )
    }

    fun init() {
        Logger.eventNode.addListener(EntityDeathEvent::class.java, ::onDeath)
    }
}
