package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Item
import net.kyori.adventure.text.Component
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.network.packet.server.play.ChangeGameStatePacket

object PlayerDeathListener {
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.player
        val killer = Combat.takeKiller(player)

        val damageType = player.lastDamageSource?.type
        val isExplosion = damageType == DamageType.PLAYER_EXPLOSION || damageType == DamageType.EXPLOSION

        // Pick the kill message by cause. Deaths we don't attribute (fall, void,
        // ...) fall through to `return`, keeping the default message.
        val message: Component =
            when {
                isExplosion && killer != null ->
                    Component.translatable("death.attack.explosion.player", player.name, killer.name)
                isExplosion ->
                    Component.translatable("death.attack.explosion", player.name)
                killer != null -> {
                    val item = killer.itemInMainHand
                    val key = if (Item.getFromItemStack(item) != null) "death.attack.arrow.item" else "death.attack.player.item"
                    Component.translatable(key, player.name, killer.name, item.get(DataComponents.CUSTOM_NAME)!!)
                }
                else -> return
            }

        event.deathText = message
        event.chatMessage = message

        // instantly respawn the player without showing the death screen
        player.sendPacket(ChangeGameStatePacket(ChangeGameStatePacket.Reason.ENABLE_RESPAWN_SCREEN, 1f))
    }

    fun init() {
        Combat.eventNode.addListener(PlayerDeathEvent::class.java, PlayerDeathListener::onPlayerDeath)
    }
}
