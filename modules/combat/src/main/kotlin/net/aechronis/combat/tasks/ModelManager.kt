package net.aechronis.combat.tasks

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Drone
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.VehicleRegistry
import net.aechronis.combat.utils.AIM_ENTRY_COOLDOWN_GROUP
import net.aechronis.combat.utils.AIM_ENTRY_TICKS
import net.aechronis.combat.utils.gunItemModel
import net.aechronis.combat.utils.scopeHelmet
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
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
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.BlockChangePacket
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket
import net.minestom.server.network.packet.server.play.SetCooldownPacket
import net.minestom.server.network.packet.server.play.SetTimePacket
import net.minestom.server.potion.Potion
import net.minestom.server.timer.TaskSchedule

object ModelManager {
    private const val HIT_ANIMATION_HASTE_SOURCE = "combat:hit_animation"

    // we give the client time to replace the zero-scale steady ADS model before
    // changing material, so the outgoing model can render the item swap animation
    private const val AIM_EXIT_PRIME_TICKS = 2

    // client world age carries shader flags; the dimension clocks remain unchanged.
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

    private val hitAnimationDisabledPlayers = HashSet<Player>()

    private data class FakeBlocks(
        val instance: Instance,
        val positions: Set<BlockVec>,
    )

    private val fakeBlocks = HashMap<Player, FakeBlocks>()

    private data class AimEntry(
        val gun: Gun,
        val slot: Byte,
        val crouching: Boolean,
        var ticksRemaining: Int = AIM_ENTRY_TICKS,
    )

    private val aimEntries = HashMap<Player, AimEntry>()

    private data class AimExit(
        val gun: Gun,
        val slot: Byte,
        var ticksRemaining: Int = AIM_EXIT_PRIME_TICKS,
    )

    private val aimExits = HashMap<Player, AimExit>()

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
        restoreStowedGuns(player)
        val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun
        val isAiming = gun != null && Combat.playerAiming[player] == true
        val firstPersonAdsDisabled = Combat.isAdsAnimationDisabled(player.uuid)
        val showAim =
            gun != null &&
                isAiming &&
                Combat.reloadTasks[player] == null &&
                gun.hasAmmo(player)
        if (!showAim || firstPersonAdsDisabled) clearAimEntry(player)
        if (showAim || firstPersonAdsDisabled || gun == null) aimExits.remove(player)
        val isLookingAtVehicle = VehicleTickManager.playerLookingAtVehicle[player] != null
        val isPilotingDrone = VehicleRegistry.driver(player)?.vehicle is Drone
        setHitAnimationDisabled(player, gun != null || isLookingAtVehicle || isPilotingDrone, allowInstantBreaking = gun != null)
        updateFakeBlocks(player, instance, gun?.automatic == true || isLookingAtVehicle)
        if (gun == null) restoreSniperScope(player)
        if (gun == null && !isLookingAtVehicle) {
            player.sendPacket(SetTimePacket(SHADER_IDLE_TIME, instance.createTimePacket().clocks))
            return
        }

        // hide the crosshair only when the first-person aiming animation is shown.
        val hideCrosshair = showAim && !firstPersonAdsDisabled
        val shaderTime = if (hideCrosshair) SHADER_AIMING_TIME else SHADER_COMBAT_TIME
        player.sendPacket(SetTimePacket(shaderTime, instance.createTimePacket().clocks))

        if (gun == null) return

        val item = player.itemInMainHand
        val hasAmmo = gun.hasAmmo(player)

        // sniper scope
        if (gun.sniper && isAiming && hasAmmo) {
            player.sendPacket(
                EntityEquipmentPacket(player.entityId, mapOf(EquipmentSlot.HELMET to scopeHelmet(player.helmet))),
            )
            player.getAttribute(Attribute.MOVEMENT_SPEED).addModifier(sniperScopeModifier)
        } else {
            restoreSniperScope(player)
        }

        // set correct model
        if (Combat.reloadTasks[player] != null) {
            aimExits.remove(player)
            player.itemInMainHand = gunItemModel(item, gun.material, gun.itemModelReloading)
        } else if (!hasAmmo) {
            aimExits.remove(player)
            player.itemInMainHand = gunItemModel(item, gun.material, gun.itemModelEmpty)
        } else if (firstPersonAdsDisabled) {
            // keep the loaded crossbow pose for other players and F5
            // the model selects normal first-person geometry without ADS swap animations
            player.itemInMainHand =
                gunItemModel(
                    item,
                    gun.material,
                    "${gun.itemModelAiming}-third-person",
                    aiming = showAim,
                    crouching = player.pose == EntityPose.SNEAKING,
                )
        } else if (showAim) {
            val entryModel = "${gun.itemModelAiming}-enter"
            var entry = aimEntries[player]
            if (entry != null && (entry.gun !== gun || entry.slot != player.heldSlot)) {
                clearAimEntry(player)
                entry = null
            }
            if (entry == null &&
                (item.material() != Material.CROSSBOW || item.get(DataComponents.ITEM_MODEL) == "${gun.itemModelAiming}-third-person")
            ) {
                entry = AimEntry(gun, player.heldSlot, player.pose == EntityPose.SNEAKING)
                aimEntries[player] = entry
                // client use packets still arrive while on cooldown, keeping right-click
                // aiming alive, but vanilla crossbow use cannot reset the hand height.
                player.sendPacket(SetCooldownPacket(AIM_ENTRY_COOLDOWN_GROUP, AIM_ENTRY_TICKS + 1))
            }
            val entering = entry != null && entry.ticksRemaining-- > 0
            player.itemInMainHand =
                gunItemModel(
                    item,
                    gun.material,
                    if (entering) entryModel else gun.itemModelAiming,
                    aiming = true,
                    // changing pose components during entry would restart the swap.
                    crouching = if (entering) entry.crouching else player.pose == EntityPose.SNEAKING,
                    enteringAim = entering,
                )
            if (!entering) clearAimEntry(player)
        } else {
            updateAimExit(player, gun, item)
        }
    }

    private fun updateFakeBlocks(
        player: Player,
        instance: Instance,
        enabled: Boolean,
    ) {
        val positions =
            if (enabled) {
                fakeBlockOffsets
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

    private fun updateAimExit(
        player: Player,
        gun: Gun,
        item: ItemStack,
    ) {
        var exit = aimExits[player]
        if (exit != null && (exit.gun !== gun || exit.slot != player.heldSlot)) {
            aimExits.remove(player)
            exit = null
        }
        if (exit == null && item.material() == Material.CROSSBOW) {
            exit = AimExit(gun, player.heldSlot)
            aimExits[player] = exit
        }
        if (exit != null && exit.ticksRemaining-- > 0) {
            // This visually identical model replaces steady ADS immediately,
            // but restores swap scale for the material change on the next phase.
            player.itemInMainHand =
                gunItemModel(
                    item,
                    gun.material,
                    "${gun.itemModelAiming}-exit",
                    aiming = true,
                    crouching = player.pose == EntityPose.SNEAKING,
                )
        } else {
            aimExits.remove(player)
            player.itemInMainHand = gunItemModel(item, gun.material, gun.itemModel)
        }
    }

    private fun clearAimEntry(player: Player) {
        if (aimEntries.remove(player) != null) {
            player.sendPacket(SetCooldownPacket(AIM_ENTRY_COOLDOWN_GROUP, 0))
        }
    }

    private fun restoreStowedGuns(
        player: Player,
        includeHeld: Boolean = false,
    ) {
        for (slot in 0 until player.inventory.size) {
            if (!includeHeld && slot == player.heldSlot.toInt()) continue
            val item = player.inventory.getItemStack(slot)
            val gun = Item.getFromItemStack(item) as? Gun ?: continue
            if (item.material() != Material.CROSSBOW &&
                item.get(DataComponents.ITEM_MODEL) != "${gun.itemModelAiming}-third-person"
            ) {
                continue
            }
            player.inventory.setItemStack(slot, gunItemModel(item, gun.material, gun.itemModel))
        }
    }

    private fun restoreSniperScope(player: Player) {
        val removed = player.getAttribute(Attribute.MOVEMENT_SPEED).removeModifier(sniperScopeModifier)
        if (removed == null) return

        player.sendPacket(EntityEquipmentPacket(player.entityId, mapOf(EquipmentSlot.HELMET to player.helmet)))
    }

    internal fun clearPlayer(player: Player) {
        val previous = fakeBlocks.remove(player)
        if (previous != null && player.isOnline && player.instance === previous.instance) {
            for (position in previous.positions) {
                if (previous.instance.isChunkLoaded(position)) {
                    player.sendPacket(BlockChangePacket(position, previous.instance.getBlock(position)))
                }
            }
        }
        clearAimEntry(player)
        aimExits.remove(player)
        restoreStowedGuns(player, includeHeld = true)
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
                addAll(aimEntries.keys)
                addAll(aimExits.keys)
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
