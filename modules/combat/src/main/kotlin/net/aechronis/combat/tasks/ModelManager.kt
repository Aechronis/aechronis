package net.aechronis.combat.tasks

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Drone
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.VehicleRegistry
import net.aechronis.combat.utils.GunAnimation
import net.aechronis.combat.utils.GunHandSkins
import net.aechronis.combat.utils.gunAnimationPhase
import net.aechronis.combat.utils.gunItemModel
import net.aechronis.combat.utils.scopeHelmet
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EntityPose
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.attribute.AttributeModifier
import net.minestom.server.entity.attribute.AttributeOperation
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.network.packet.server.play.BlockChangePacket
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket
import net.minestom.server.network.packet.server.play.SetTimePacket
import net.minestom.server.potion.Potion
import net.minestom.server.timer.TaskSchedule

object ModelManager {
    private const val HIT_ANIMATION_HASTE_SOURCE = "combat:hit_animation"

    // Client world age carries shader flags plus a 0..499 animation phase; the
    // dimension clocks remain unchanged. Partial ticks keep the phase smooth.
    // both active values stay in 11xxx so rendertype_lines also hides fake block outlines.
    private const val SHADER_IDLE_TIME = 10000L
    private const val SHADER_COMBAT_TIME = 11000L
    private const val SHADER_AIMING_TIME = 11500L

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

    private val automaticGunFakeBlockOffsets =
        buildList {
            for (y in 1..3) {
                for (x in -2..2) {
                    for (z in -2..2) {
                        add(Vec(x.toDouble(), y.toDouble(), z.toDouble()))
                    }
                }
            }
        }

    private val hitAnimationDisabledPlayers = HashSet<Player>()

    private data class FakeBlocks(
        val instance: Instance,
        val positions: Set<BlockVec>,
    )

    private val fakeBlocks = HashMap<Player, FakeBlocks>()

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

    /** Returns false when a drone owns world-age telemetry instead of the gun clock. */
    internal fun syncShaderTime(
        player: Player,
        worldAge: Long,
    ): Boolean {
        val packet = shaderTimePacket(player, worldAge) ?: return false
        player.sendPacket(packet)
        return true
    }

    internal fun shaderTimePacket(
        player: Player,
        worldAge: Long,
        ammoAvailable: Boolean? = null,
    ): SetTimePacket? {
        val instance = player.instance ?: return null
        if (VehicleRegistry.driver(player)?.vehicle is Drone || Drone.crashStaticCamera(player) != null) return null
        val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun
        val hideCrosshair =
            gun != null &&
                Combat.playerAiming[player] == true &&
                Combat.reloadTasks[player] == null &&
                (ammoAvailable ?: gun.hasAmmo(player))
        val band =
            when {
                hideCrosshair -> SHADER_AIMING_TIME
                gun != null || VehicleTickManager.playerLookingAtVehicle[player] != null -> SHADER_COMBAT_TIME
                else -> SHADER_IDLE_TIME
            }
        return SetTimePacket(band + gunAnimationPhase(worldAge), instance.createTimePacket().clocks)
    }

    fun updateModel(player: Player) {
        val instance = player.instance ?: return
        restoreStowedGuns(player)
        val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun
        val isAiming = gun != null && Combat.playerAiming[player] == true
        val showAim =
            gun != null &&
                isAiming &&
                Combat.reloadTasks[player] == null &&
                gun.hasAmmo(player)
        val isLookingAtVehicle = VehicleTickManager.playerLookingAtVehicle[player] != null
        val isPilotingDrone = VehicleRegistry.driver(player)?.vehicle is Drone
        val droneOwnsShaderTime = isPilotingDrone || Drone.crashStaticCamera(player) != null
        GunAnimation.update(player, gun, viewModelVisible = !droneOwnsShaderTime)
        setHitAnimationDisabled(player, gun != null || isLookingAtVehicle || isPilotingDrone, allowInstantBreaking = gun != null)
        updateFakeBlocks(player, instance, gun?.automatic == true || isLookingAtVehicle, automaticGun = gun?.automatic == true)
        if (gun == null) restoreSniperScope(player)
        // Empty-handed players and late viewers need this same instance phase to
        // see another player's ongoing animation. Only the HUD band is personal.
        syncShaderTime(player, instance.worldAge)
        if (gun == null) return

        val hasAmmo = gun.hasAmmo(player)
        val preserveAction = droneOwnsShaderTime || GunAnimation.isSettling(player, gun) || GunAnimation.isFiring(player, gun)
        if (!preserveAction) GunAnimation.updateAim(player, gun, showAim)

        // sniper scope
        if (gun.sniper && showAim && GunAnimation.isFullyAimed(player, gun)) {
            player.sendPacket(
                EntityEquipmentPacket(player.entityId, mapOf(EquipmentSlot.HELMET to scopeHelmet(player.helmet))),
            )
            player.getAttribute(Attribute.MOVEMENT_SPEED).addModifier(sniperScopeModifier)
        } else {
            restoreSniperScope(player)
        }

        GunHandSkins.update(player)
        // Keep the accepted action's carrier stable while its shader runs.
        // Manual scoped actions exit ADS within that carrier before re-aiming.
        if (preserveAction) return
        val model =
            when {
                Combat.reloadTasks[player] != null -> gun.itemModelReloading
                !hasAmmo -> gun.itemModelEmpty
                showAim -> gun.itemModelAiming
                else -> gun.itemModel
            }
        // These first-person models implement pose interpolation in the
        // shader; changing the vanilla material only supplies the F5 pose.
        player.itemInMainHand =
            gunItemModel(
                player.itemInMainHand,
                gun.material,
                model,
                aiming = showAim,
                crouching = player.pose == EntityPose.SNEAKING,
            )
    }

    private fun updateFakeBlocks(
        player: Player,
        instance: Instance,
        enabled: Boolean,
        automaticGun: Boolean,
    ) {
        val positions =
            if (enabled) {
                (if (automaticGun) automaticGunFakeBlockOffsets else fakeBlockOffsets)
                    .map { player.position.add(it).asBlockVec() }
                    .filter { instance.isChunkLoaded(it) && instance.getBlock(it).isAir }
                    .toSet()
            } else {
                emptySet()
            }
        val previous = fakeBlocks.remove(player)
        if (previous?.instance === instance) {
            for (position in previous.positions - positions) {
                if (instance.isChunkLoaded(position)) {
                    player.sendPacket(BlockChangePacket(position, instance.getBlock(position)))
                }
            }
        }
        if (positions.isEmpty()) return
        // resend even unchanged positions as the client removes these on each shot
        // restore only positions we leave, avoiding an air gap between refreshes
        for (position in positions) player.sendPacket(BlockChangePacket(position, Block.SCULK_VEIN))
        fakeBlocks[player] = FakeBlocks(instance, positions)
    }

    private fun restoreStowedGuns(player: Player) {
        for (slot in 0 until player.inventory.size) {
            if (slot == player.heldSlot.toInt()) continue
            GunAnimation.restoreSlot(player, slot)
        }
    }

    private fun restoreSniperScope(player: Player) {
        val removed = player.getAttribute(Attribute.MOVEMENT_SPEED).removeModifier(sniperScopeModifier)
        if (removed == null) return

        player.sendPacket(EntityEquipmentPacket(player.entityId, mapOf(EquipmentSlot.HELMET to player.helmet)))
    }

    internal fun clearPlayer(player: Player) {
        GunAnimation.cancel(player)
        val previous = fakeBlocks.remove(player)
        if (previous != null && player.isOnline && player.instance === previous.instance) {
            for (position in previous.positions) {
                if (previous.instance.isChunkLoaded(position)) {
                    player.sendPacket(BlockChangePacket(position, previous.instance.getBlock(position)))
                }
            }
        }
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
                addAll(fakeBlocks.keys)
                runCatching { MinecraftServer.getConnectionManager().onlinePlayers }
                    .getOrNull()
                    ?.let(::addAll)
            }
        val failures = ArrayList<Throwable>()

        for (player in players) {
            try {
                (Item.getFromItemStack(player.itemInMainHand) as? Gun)?.let { gun ->
                    player.itemInMainHand = gunItemModel(player.itemInMainHand, gun.material, gun.itemModel)
                }
                restoreSniperScope(player)
                clearPlayer(player)

                val instance = player.instance
                if (player.isOnline && instance != null) {
                    player.sendPacket(SetTimePacket(SHADER_IDLE_TIME, instance.createTimePacket().clocks))
                    player.sendActionBar(Component.empty())
                }
            } catch (exception: Throwable) {
                failures.add(exception)
            }
        }

        hitAnimationDisabledPlayers.clear()
        fakeBlocks.clear()
        GunAnimation.shutdown()
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
        allowInstantBreaking: Boolean = false,
    ) {
        // guns use a tool rule that only mines sculk veins, other combat interactions
        // keep the old blanket mining lock - update this even when animations stay disabled.
        val blockBreakSpeed = player.getAttribute(Attribute.BLOCK_BREAK_SPEED)
        if (disabled && !allowInstantBreaking) {
            blockBreakSpeed.addModifier(blockBreakSpeedModifier)
        } else {
            blockBreakSpeed.removeModifier(blockBreakSpeedModifier)
        }
        if (disabled) {
            if (!hitAnimationDisabledPlayers.add(player)) return
            player.getAttribute(Attribute.ATTACK_SPEED).addModifier(attackSpeedModifier)
            HasteEffectManager.set(
                player,
                HIT_ANIMATION_HASTE_SOURCE,
                amplifier = 10,
                durationTicks = Potion.INFINITE_DURATION,
            )
        } else {
            if (!hitAnimationDisabledPlayers.remove(player)) return
            player.getAttribute(Attribute.ATTACK_SPEED).removeModifier(attackSpeedModifier)
            HasteEffectManager.clear(player, HIT_ANIMATION_HASTE_SOURCE)
        }
    }
}
