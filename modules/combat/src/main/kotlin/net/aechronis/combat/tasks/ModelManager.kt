package net.aechronis.combat.tasks

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.TurretScope
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.objects.VehicleSeatHotbar
import net.aechronis.combat.utils.GUN_ANIMATION_CLOCK_TICKS
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
import net.minestom.server.event.player.PlayerPacketOutEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.network.packet.server.play.BlockChangePacket
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket
import net.minestom.server.network.packet.server.play.SetTimePacket
import net.minestom.server.potion.Potion
import net.minestom.server.timer.TaskSchedule
import java.util.concurrent.ConcurrentHashMap

object ModelManager {
    private const val HIT_ANIMATION_HASTE_SOURCE = "combat:hit_animation"

    // Client world age carries shader flags plus a 0..499 animation phase; the
    // dimension clocks remain unchanged. Partial ticks keep the phase smooth.
    // both active values stay in 11xxx so rendertype_lines also hides fake block outlines.
    private const val SHADER_IDLE_TIME = 10000L
    private const val SHADER_COMBAT_TIME = 11000L
    private const val SHADER_AIMING_TIME = 11500L
    private const val SHADER_CLOCK_REFRESH_TICKS = 100L

    private data class ShaderClock(
        val instance: Instance,
        val band: Long,
        val worldAge: Long,
        val sentAtNanos: Long = System.nanoTime(),
    ) {
        fun needsRefresh(
            instance: Instance,
            band: Long,
            worldAge: Long,
        ): Boolean =
            this.instance !== instance ||
                this.band != band ||
                worldAge - this.worldAge !in 0 until SHADER_CLOCK_REFRESH_TICKS ||
                Math.floorDiv(worldAge, GUN_ANIMATION_CLOCK_TICKS) != Math.floorDiv(this.worldAge, GUN_ANIMATION_CLOCK_TICKS) ||
                // The client keeps ticking during server lag. Do not let its HUD
                // band expire while waiting for the server to reach the phase wrap.
                System.nanoTime() - sentAtNanos >=
                minOf(SHADER_CLOCK_REFRESH_TICKS, GUN_ANIMATION_CLOCK_TICKS - gunAnimationPhase(this.worldAge)) * 50_000_000L
    }

    private val shaderClocks = ConcurrentHashMap<Player, ShaderClock>()

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
    private val customViewPlayers = ConcurrentHashMap.newKeySet<Player>()

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

    internal fun initListeners() {
        Combat.eventNode.addListener(PlayerSpawnEvent::class.java) { shaderClocks.remove(it.player) }
        Combat.eventNode.addListener(PlayerPacketOutEvent::class.java) { event ->
            val packet = event.packet as? SetTimePacket ?: return@addListener
            if (event.isCancelled) return@addListener
            val clock = shaderClocks[event.player] ?: return@addListener
            // Respawns and world-time changes can replace our clock. Observe only:
            // moving a packet here could split an atomic firing bundle.
            if (packet.gameTime !in clock.band until clock.band + GUN_ANIMATION_CLOCK_TICKS) {
                shaderClocks.remove(event.player, clock)
            }
        }
    }

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

    /** Tracks a temporary camera or shader clock that can outlive a vehicle ride. */
    fun setCustomView(
        player: Player,
        enabled: Boolean,
    ) {
        if (enabled) customViewPlayers.add(player) else customViewPlayers.remove(player)
        shaderClocks.remove(player)
    }

    fun hasCustomView(player: Player): Boolean = Vehicle.drivenBy(player)?.customDriverView == true || player in customViewPlayers

    /** Returns false when a custom view owns world-age telemetry instead of the gun clock. */
    internal fun syncShaderTime(
        player: Player,
        worldAge: Long,
        force: Boolean = true,
    ): Boolean {
        val instance = player.instance
        if (instance == null || hasCustomView(player)) {
            shaderClocks.remove(player)
            return false
        }
        val band = shaderTimeBand(player)
        if (!force && shaderClocks[player]?.needsRefresh(instance, band, worldAge) == false) return true
        val packet = SetTimePacket(band + gunAnimationPhase(worldAge), instance.createTimePacket().clocks)
        player.sendPacket(packet)
        shaderClocks[player] = ShaderClock(instance, band, worldAge)
        return true
    }

    internal fun shaderTimePacket(
        player: Player,
        worldAge: Long,
    ): SetTimePacket? {
        val instance = player.instance ?: return null
        if (hasCustomView(player)) return null
        return SetTimePacket(shaderTimeBand(player) + gunAnimationPhase(worldAge), instance.createTimePacket().clocks)
    }

    private fun shaderTimeBand(player: Player): Long {
        val gun = if (VehicleSeatHotbar.isActive(player)) null else Item.getFromItemStack(player.itemInMainHand) as? Gun
        val hideCrosshair =
            gun != null &&
                Combat.playerStates[player]?.aiming == true &&
                Combat.playerStates[player]?.reloadTask == null
        return when {
            TurretScope.isActive(player) || hideCrosshair -> SHADER_AIMING_TIME
            gun != null ||
                VehicleSeatHotbar.isActive(
                    player,
                ) ||
                VehicleInteractionTracker[player] != null -> SHADER_COMBAT_TIME
            else -> SHADER_IDLE_TIME
        }
    }

    fun updateModel(player: Player) {
        val instance = player.instance ?: return
        restoreStowedGuns(player)
        val gun = if (VehicleSeatHotbar.isActive(player)) null else Item.getFromItemStack(player.itemInMainHand) as? Gun
        val isAiming = gun != null && Combat.playerStates[player]?.aiming == true
        val showAim =
            gun != null &&
                isAiming &&
                Combat.playerStates[player]?.reloadTask == null
        val isLookingAtVehicle = VehicleInteractionTracker[player] != null
        val hasCustomDriverView = Vehicle.drivenBy(player)?.customDriverView == true
        val customViewOwnsShaderTime = hasCustomView(player)
        val turretScopeActive = TurretScope.isActive(player)
        GunAnimation.update(player, gun, viewModelVisible = !customViewOwnsShaderTime && !turretScopeActive)
        setHitAnimationDisabled(
            player,
            gun != null || isLookingAtVehicle || hasCustomDriverView || turretScopeActive || VehicleSeatHotbar.isActive(player),
            allowInstantBreaking = gun != null && !turretScopeActive,
        )
        updateFakeBlocks(
            player,
            instance,
            !turretScopeActive && (gun?.automatic == true || isLookingAtVehicle),
            automaticGun = gun?.automatic == true,
        )
        if (gun == null || turretScopeActive) restoreSniperScope(player)
        // Let the client clock advance between state changes and occasional drift
        // corrections. Refresh at phase wrap before the clock enters another HUD band.
        // Action publications still force a matching clock alongside their start phase.
        syncShaderTime(player, instance.worldAge, force = false)
        // The turret uses the native camera and the normal shader clock, but owns
        // the sight and hand visibility instead of the handheld gun's models.
        if (gun == null || turretScopeActive) return

        val hasAmmo = gun.hasAmmo(player)
        val preserveAction = customViewOwnsShaderTime || GunAnimation.isSettling(player, gun) || GunAnimation.isFiring(player, gun)
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
                Combat.playerStates[player]?.reloadTask != null -> gun.itemModelReloading
                showAim -> gun.itemModelAiming
                !hasAmmo -> gun.itemModelEmpty
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
                    .filter { instance.isChunkLoaded(it) && instance.getBlock(it).air() }
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
        shaderClocks.remove(player)
        customViewPlayers.remove(player)
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
                addAll(customViewPlayers)
                addAll(fakeBlocks.keys)
                addAll(shaderClocks.keys)
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
        customViewPlayers.clear()
        shaderClocks.clear()
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
