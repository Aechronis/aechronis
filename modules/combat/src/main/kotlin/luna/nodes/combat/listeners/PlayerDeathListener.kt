package luna.nodes.combat.listeners

import luna.nodes.combat.Combat
import luna.nodes.combat.objects.Item
import net.kyori.adventure.text.Component
import net.minestom.server.collision.BoundingBox
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityPose
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.metadata.avatar.MannequinMeta
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerRespawnEvent
import net.minestom.server.network.player.ResolvableProfile

object PlayerDeathListener {
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.player
        val killer = Combat.playerKillers[player] ?: return
        val item = killer.itemInMainHand
        val gun = Item.getFromItemStack(killer.itemInMainHand)

        Combat.playerKillers[player] = null

        event.deathText
        event.chatMessage

        if (gun != null) {
            event.deathText =
                Component.translatable("death.attack.arrow.item", player.name, killer.name, item.get(DataComponents.CUSTOM_NAME)!!)
            event.chatMessage =
                Component.translatable("death.attack.arrow.item", player.name, killer.name, item.get(DataComponents.CUSTOM_NAME)!!)
        } else {
            event.deathText =
                Component.translatable("death.attack.player.item", player.name, killer.name, item.get(DataComponents.CUSTOM_NAME)!!)
            event.chatMessage =
                Component.translatable("death.attack.player.item", player.name, killer.name, item.get(DataComponents.CUSTOM_NAME)!!)
        }

        // make actual player invisible and spawn corpse

        val corpse = EntityCreature(EntityType.MANNEQUIN)

        corpse.editEntityMeta(MannequinMeta::class.java, { meta ->
            val profile =
                ResolvableProfile(
                    player.skin,
                )
            meta.setProfile(profile)
        })

        corpse.setInstance(player.instance, player.position)
        corpse.boundingBox = BoundingBox(0.0, 0.0, 0.0)

        corpse.setInstance(player.instance, player.position)
        corpse.boundingBox = BoundingBox(0.0, 0.0, 0.0)
        corpse.velocity =
            killer.position
                .direction()
                .mul(10.0)
                .withY(2.0)
        corpse.pose = EntityPose.SWIMMING

        player.updateViewableRule { false }
        player.boundingBox = BoundingBox(0.0, 0.0, 0.0)
    }

    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val player = event.player

        player.updateViewableRule { true }
        player.boundingBox = player.entityType.registry()!!.boundingBox()
    }

    fun init() {
        Combat.eventNode.addListener(PlayerDeathEvent::class.java, PlayerDeathListener::onPlayerDeath)
        Combat.eventNode.addListener(PlayerRespawnEvent::class.java, PlayerDeathListener::onPlayerRespawn)
    }
}
