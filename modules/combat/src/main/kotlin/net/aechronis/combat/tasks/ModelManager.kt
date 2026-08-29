package net.aechronis.combat.tasks

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Drone
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Vehicle
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.attribute.AttributeModifier
import net.minestom.server.entity.attribute.AttributeOperation
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.BlockChangePacket
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket
import net.minestom.server.network.packet.server.play.SetTimePacket
import net.minestom.server.potion.Potion
import net.minestom.server.timer.TaskSchedule

object ModelManager {
    private const val HIT_ANIMATION_HASTE_SOURCE = "combat:hit_animation"

    // X X X layer 1 A X A layer 2
    // X H X         X X X
    // X X X         A X A
    private val fakeBlockOffsets =
        buildList {
            for (x in -1..1) {
                for (z in -1..1) {
                    add(Vec(x.toDouble(), 1.0, z.toDouble()))
                }
            }
            add(Vec(0.0, 2.0, 0.0))
            add(Vec(-1.0, 2.0, 0.0))
            add(Vec(1.0, 2.0, 0.0))
            add(Vec(0.0, 2.0, -1.0))
            add(Vec(0.0, 2.0, 1.0))
        }

    private val hitAnimationDisabledPlayers = HashSet<Player>()
    private val sniperScopeModifier =
        AttributeModifier(
            "aechronis:sniper_scope",
            -1.0,
            AttributeOperation.ADD_MULTIPLIED_TOTAL,
        )
    private val attackSpeedModifier =
        AttributeModifier(
            "aechronis:disable_hit_animation",
            1024.0,
            AttributeOperation.ADD_VALUE,
        )
    private val blockBreakSpeedModifier =
        AttributeModifier(
            "aechronis:disable_block_breaking",
            -1.0,
            AttributeOperation.ADD_MULTIPLIED_TOTAL,
        )

    // run scheduler for changing item models, animations etc.
    fun start() {
        ModuleScheduler
            .buildTask {
                for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                    updateModel(player)
                }
            }.repeat(TaskSchedule.tick(1))
            .schedule()
    }

    fun updateModel(player: Player) {
        val instance = player.instance ?: return
        val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun
        val isLookingAtVehicle = VehicleTickManager.playerLookingAtVehicle[player] != null
        val isPilotingDrone = Vehicle.playerVehicle[player] is Drone
        setHitAnimationDisabled(player, gun != null || isLookingAtVehicle || isPilotingDrone)
        if (gun == null) restoreSniperScope(player)
        if (gun == null && !isLookingAtVehicle) {
            player.sendPacket(SetTimePacket(10000, instance.createTimePacket().clocks))
            return
        }

        // when gun is automatic, or looking at vehicle, show player fake blocks so they keep sending animation packets when holding down left/right click
        // we hide the block + outline with a resource pack shader
        if (gun?.automatic == true || isLookingAtVehicle) {
            for (offset in fakeBlockOffsets) {
                val pos: Pos = player.position.add(offset)
                if (!instance.isChunkLoaded(pos)) continue
                if (instance.getBlock(pos).isAir) {
                    player.sendPacket(BlockChangePacket(pos, Block.GLOW_LICHEN))
                    ModuleScheduler
                        .buildTask { player.sendPacket(BlockChangePacket(pos, Block.AIR)) }
                        .delay(TaskSchedule.tick(1))
                        .schedule()
                }
            }
        }

        // hide block outline
        player.sendPacket(SetTimePacket(11000, instance.createTimePacket().clocks))

        if (gun == null) return

        val item = player.itemInMainHand
        val isAiming = Combat.playerAiming[player] == true
        val hasAmmo = gun.hasAmmo(player)

        // sniper scope
        if (gun.sniper && isAiming && hasAmmo) {
            player.sendPacket(
                EntityEquipmentPacket(player.entityId, mapOf(EquipmentSlot.HELMET to ItemStack.of(Material.CARVED_PUMPKIN))),
            )
            player.getAttribute(Attribute.MOVEMENT_SPEED).addModifier(sniperScopeModifier)
        } else {
            restoreSniperScope(player)
        }

        // set correct model
        if (Combat.reloadTasks[player] != null) {
            player.itemInMainHand = item.withItemModel(gun.itemModelReloading)
        } else if (!hasAmmo) {
            player.itemInMainHand = item.withItemModel(gun.itemModelEmpty)
        } else if (isAiming && !Combat.isAdsAnimationDisabled(player.uuid)) {
            player.itemInMainHand = item.withItemModel(gun.itemModelAiming)
        } else {
            player.itemInMainHand = item.withItemModel(gun.itemModel)
        }
    }

    private fun restoreSniperScope(player: Player) {
        val removed = player.getAttribute(Attribute.MOVEMENT_SPEED).removeModifier(sniperScopeModifier)
        if (removed == null) return

        player.sendPacket(EntityEquipmentPacket(player.entityId, mapOf(EquipmentSlot.HELMET to player.helmet)))
    }

    internal fun clearPlayer(player: Player) {
        player.getAttribute(Attribute.MOVEMENT_SPEED).removeModifier(sniperScopeModifier)
        hitAnimationDisabledPlayers.remove(player)
        player.getAttribute(Attribute.ATTACK_SPEED).removeModifier(attackSpeedModifier)
        player.getAttribute(Attribute.BLOCK_BREAK_SPEED).removeModifier(blockBreakSpeedModifier)
        HasteEffectManager.clear(player, HIT_ANIMATION_HASTE_SOURCE)
    }

    /** Restores all client-only models and server-side modifiers owned by combat. */
    fun shutdown() {
        val players =
            buildSet {
                addAll(hitAnimationDisabledPlayers)
                runCatching { MinecraftServer.getConnectionManager().onlinePlayers }
                    .getOrNull()
                    ?.let(::addAll)
            }
        val failures = ArrayList<Throwable>()

        for (player in players) {
            try {
                (Item.getFromItemStack(player.itemInMainHand) as? Gun)?.let { gun ->
                    player.itemInMainHand = player.itemInMainHand.withItemModel(gun.itemModel)
                }
                restoreSniperScope(player)
                clearPlayer(player)

                val instance = player.instance
                if (player.isOnline && instance != null) {
                    for (offset in fakeBlockOffsets) {
                        val position = player.position.add(offset)
                        if (instance.isChunkLoaded(position)) {
                            player.sendPacket(BlockChangePacket(position, instance.getBlock(position)))
                        }
                    }
                    player.sendPacket(SetTimePacket(10000, instance.createTimePacket().clocks))
                    player.sendActionBar(Component.empty())
                }
            } catch (exception: Throwable) {
                failures.add(exception)
            }
        }

        hitAnimationDisabledPlayers.clear()
        try {
            HasteEffectManager.shutdown()
        } catch (exception: Throwable) {
            failures.add(exception)
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Failed to restore ${failures.size} combat player model(s)").apply {
                failures.forEach(::addSuppressed)
            }
        }
    }

    internal fun setHitAnimationDisabled(
        player: Player,
        disabled: Boolean,
    ) {
        if (disabled) {
            if (!hitAnimationDisabledPlayers.add(player)) return
            player.getAttribute(Attribute.ATTACK_SPEED).addModifier(attackSpeedModifier)
            player.getAttribute(Attribute.BLOCK_BREAK_SPEED).addModifier(blockBreakSpeedModifier)
            HasteEffectManager.set(
                player,
                HIT_ANIMATION_HASTE_SOURCE,
                amplifier = 10,
                durationTicks = Potion.INFINITE_DURATION,
            )
        } else {
            if (!hitAnimationDisabledPlayers.remove(player)) return
            player.getAttribute(Attribute.ATTACK_SPEED).removeModifier(attackSpeedModifier)
            player.getAttribute(Attribute.BLOCK_BREAK_SPEED).removeModifier(blockBreakSpeedModifier)
            HasteEffectManager.clear(player, HIT_ANIMATION_HASTE_SOURCE)
        }
    }
}
