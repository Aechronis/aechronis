package net.aechronis.combat.commands

import net.aechronis.combat.listeners.HatListener
import net.aechronis.utils.Command
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.metadata.avatar.MannequinMeta
import net.minestom.server.network.player.ResolvableProfile

class HatsCommand : Command("hats", null, "hat", "h") {
    init {
        setDefaultExecutor { player, _ ->
            HatListener.playerCamera[player.uuid] = Entity(EntityType.ITEM_DISPLAY)
            HatListener.playerMannequin[player.uuid] = LivingEntity(EntityType.MANNEQUIN)

            HatListener.playerMannequin[player.uuid]?.instance = player.instance
            HatListener.playerCamera[player.uuid]?.instance = player.instance

            val pos = player.position
            HatListener.playerMannequin[player.uuid]?.teleport(pos)
            HatListener.playerCamera[player.uuid]?.teleport(
                pos.withView(pos.yaw + 180f, 40F).add(pos.direction().mul(2.0)).withY(pos.y + 3),
            )

            (HatListener.playerMannequin[player.uuid]?.entityMeta as MannequinMeta).profile = ResolvableProfile(player.skin)

            HatListener.playerMannequin[player.uuid]?.spawn()
            HatListener.playerCamera[player.uuid]?.spawn()
            HatListener.playerCamera[player.uuid]?.setNoGravity(true)

            player.gameMode = GameMode.SPECTATOR
            player.spectate(HatListener.playerCamera[player.uuid])
        }
    }
}
