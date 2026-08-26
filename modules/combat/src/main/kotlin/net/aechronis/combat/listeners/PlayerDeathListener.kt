package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.utils.CombatDamageKind
import net.aechronis.combat.utils.LagCompensation
import net.aechronis.combat.utils.clearCombatAttribution
import net.aechronis.combat.utils.combatDamageKind
import net.aechronis.combat.utils.combatWeapon
import net.kyori.adventure.text.Component
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.network.packet.server.play.ChangeGameStatePacket

object PlayerDeathListener {
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.player
        val damage = Combat.activeDamage(player)
        val killer = damage?.attacker
        val weapon = damage?.combatWeapon()
        val damageKind = damage?.combatDamageKind()
        damage?.clearCombatAttribution()

        exitVehicles(player)
        KeyPressListener.playerInputEvent.remove(player)
        Combat.entityLastDamageTime.remove(player)
        Combat.grantRespawnProtection(player)
        LagCompensation.resetHistory(player)

        val message = combatDeathMessage(player.name, killer?.let(::attackerName), weapon, damageKind, killer === player)
        if (message != null) {
            event.deathText = message
            event.chatMessage = message
        }

        // instantly respawn the player without showing the death screen
        player.sendPacket(ChangeGameStatePacket(ChangeGameStatePacket.Reason.ENABLE_RESPAWN_SCREEN, 1f))
    }

    internal fun exitVehicles(player: Player) {
        Vehicle.playerVehicle[player]?.onExit(player)
        Vehicle.passengerVehicle[player]?.onPassengerExit(player)
    }

    internal fun attackerName(attacker: Entity): Component =
        when (attacker) {
            is Player -> attacker.name
            else -> attacker.get(DataComponents.CUSTOM_NAME) ?: Component.translatable(attacker.entityType.translationKey())
        }

    internal fun weaponDeathMessage(
        victim: Component,
        killer: Component,
        weapon: Component,
        damageKind: CombatDamageKind,
    ): Component {
        val key = if (damageKind == CombatDamageKind.PROJECTILE) "death.attack.arrow.item" else "death.attack.player.item"
        return Component.translatable(key, victim, killer, weapon)
    }

    internal fun combatDeathMessage(
        victim: Component,
        killer: Component?,
        weapon: Component?,
        damageKind: CombatDamageKind?,
        selfInflicted: Boolean,
    ): Component? =
        when {
            damageKind == CombatDamageKind.EXPLOSION && selfInflicted -> {
                Component.translatable("death.attack.explosion", victim)
            }

            killer != null && weapon != null && damageKind != null -> {
                weaponDeathMessage(victim, killer, weapon, damageKind)
            }

            killer != null && damageKind == CombatDamageKind.EXPLOSION -> {
                Component.translatable("death.attack.explosion.player", victim, killer)
            }

            else -> {
                null
            }
        }

    fun init() {
        Combat.eventNode.addListener(PlayerDeathEvent::class.java, PlayerDeathListener::onPlayerDeath)
    }
}
