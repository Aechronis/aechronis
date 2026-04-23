package net.aechronis.combat.tasks

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.BlockChangePacket
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket
import net.minestom.server.network.packet.server.play.TimeUpdatePacket
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect
import net.minestom.server.timer.TaskSchedule

object ModelManager {
    // run scheduler for changing item models, animations etc.
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
        if (gun != null || VehicleTickManager.playerLookingAtVehicle[player] != null) {
            disableHitAnimation(player)

            // when gun is automatic, or looking at vehicle, show player fake blocks so they keep sending animation packets when holding down left/right click
            // we hide the block + outline with a resource pack shader
            if (gun?.automatic == true || VehicleTickManager.playerLookingAtVehicle[player] != null) {
                for (y in 1..2) {
                    for (x in -2..2) {
                        for (z in -2..2) {
                            val pos: Pos = player.position.add(x.toDouble(), y.toDouble(), z.toDouble())
                            if (player.instance?.getBlock(pos)?.isAir ?: false) {
                                player.sendPacket(BlockChangePacket(pos, Block.GLOW_LICHEN))
                                MinecraftServer
                                    .getSchedulerManager()
                                    .buildTask { player.sendPacket(BlockChangePacket(pos, Block.AIR)) }
                                    .delay(TaskSchedule.tick(1))
                                    .schedule()
                            }
                        }
                    }
                }
            }

            // hide block outline
            player.sendPacket(TimeUpdatePacket(11000, player.instance.time, false))

            if (gun == null) return

            if (Combat.playerFiring[player] == true) {
                gun.fire(player)
            }

            val item = player.itemInMainHand

            // sniper scope
            if (gun.sniper && Combat.playerAiming[player] == true && gun.hasAmmo(player)) {
                player.sendPacket(
                    EntityEquipmentPacket(player.entityId, mapOf(EquipmentSlot.HELMET to ItemStack.of(Material.CARVED_PUMPKIN))),
                )
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
            player.sendPacket(TimeUpdatePacket(10000, player.instance?.time ?: 0, false))
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
