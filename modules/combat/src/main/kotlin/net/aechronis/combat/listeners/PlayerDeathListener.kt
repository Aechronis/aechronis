package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Item
import net.kyori.adventure.text.Component
import net.minestom.server.collision.BoundingBox
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityPose
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.entity.metadata.avatar.MannequinMeta
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.network.packet.server.play.ChangeGameStatePacket
import net.minestom.server.network.player.ResolvableProfile

object PlayerDeathListener {
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.player
        val killer = Combat.playerKillers[player]
        Combat.playerKillers[player] = null

        val damageType = player.lastDamageSource?.type
        val isExplosion = damageType == DamageType.PLAYER_EXPLOSION || damageType == DamageType.EXPLOSION

        // Pick the kill message by cause. Deaths we don't attribute (fall, void,
        // ...) fall through to `return`, keeping the default message and no corpse.
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

        spawnCorpse(player, killer, isExplosion)

        // instantly respawn the player without showing the death screen
        player.sendPacket(ChangeGameStatePacket(ChangeGameStatePacket.Reason.ENABLE_RESPAWN_SCREEN, 1f))
    }

    // drop a skin-matched mannequin where the player died, flung off the killing blow
    private fun spawnCorpse(
        player: Player,
        killer: Player?,
        isExplosion: Boolean,
    ) {
        val corpse = EntityCreature(EntityType.MANNEQUIN)
        corpse.editEntityMeta(MannequinMeta::class.java) { meta ->
            meta.profile = ResolvableProfile(player.skin)
        }
        corpse.setInstance(player.instance, player.position)
        corpse.boundingBox = BoundingBox(0.0, 0.0, 0.0)
        corpse.velocity = corpseLaunch(player, killer, isExplosion).mul(10.0).withY(2.0)
        corpse.pose = EntityPose.SWIMMING
    }

    // horizontal fling direction for the corpse: away from the blast for an
    // explosion, otherwise along the killer's facing
    private fun corpseLaunch(
        player: Player,
        killer: Player?,
        isExplosion: Boolean,
    ): Vec {
        if (isExplosion) {
            val center = player.lastDamageSource?.sourcePosition
            if (center != null) {
                val away = Vec(player.position.x - center.x(), 0.0, player.position.z - center.z())
                if (away.lengthSquared() > 1.0e-6) return away.normalize()
            }
            return Vec(0.0, 1.0, 0.0)
        }
        return (killer?.position ?: player.position).direction()
    }

    fun init() {
        Combat.eventNode.addListener(PlayerDeathEvent::class.java, PlayerDeathListener::onPlayerDeath)
    }
}
