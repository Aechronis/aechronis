package luna.nodes.combat.tasks

import luna.nodes.combat.Combat
import luna.nodes.combat.objects.Gun
import luna.nodes.combat.objects.Item
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect
import net.minestom.server.timer.TaskSchedule

object ModelManager {
    // run scheduler for giving income
    fun start() {
        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                    updateModel(player)
                }
            }.repeat(TaskSchedule.tick(1))
            .schedule()
    }

    fun updateModel(player: Player) {
        val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun
        if (gun != null) {
            disableHitAnimation(player)

            if (Combat.playerFiring[player] == true) {
                gun.fire(player)
            }

            val item = player.itemInMainHand

            // sniper scope
            if (gun.sniper && Combat.playerAiming[player] == true && gun.hasAmmo(player)) {
                player.sendPacket(EntityEquipmentPacket(player.entityId, mapOf(EquipmentSlot.HELMET to ItemStack.of(Material.CARVED_PUMPKIN))))
                player.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(0.0)
            } else {
                player.sendPacket(EntityEquipmentPacket(player.entityId, mapOf(EquipmentSlot.HELMET to ItemStack.AIR)))
                player.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(0.1)
            }

            // set correct model
            if (Combat.reloadTasks[player] != null) {
                player.itemInMainHand = item.withItemModel(gun.itemModelReloading)
            } else if (!gun.hasAmmo(player)) {
                player.itemInMainHand = item.withItemModel(gun.itemModelEmpty)
            } else if (Combat.playerAiming[player] == true) {
                player.itemInMainHand = item.withItemModel(gun.itemModelAiming)
            } else {
                player.itemInMainHand = item.withItemModel(gun.itemModel)
            }
        } else {
            enableHitAnimation(player)
        }
    }

    fun disableHitAnimation(player: Player) {
        player.getAttribute(Attribute.ATTACK_SPEED).setBaseValue(999.0) // avoids vanilla hit animation when player clicks to fire
        player.getAttribute(Attribute.BLOCK_BREAK_SPEED).setBaseValue(0.0) // cant break blocks, imperative because of haste
        player.addEffect(Potion(PotionEffect.HASTE, 10, 2))
    }

    // set all attributes modified by disableHitAnimation to their defaults
    fun enableHitAnimation(player: Player) {
        player.getAttribute(Attribute.ATTACK_SPEED).setBaseValue(4.0)
        player.getAttribute(Attribute.BLOCK_BREAK_SPEED).setBaseValue(1.0)
        // we don't need to worry about haste as it only lasts 2 ticks
    }
}
