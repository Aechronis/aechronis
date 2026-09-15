package net.aechronis.combat.utils

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.aechronis.combat.tasks.ModelManager
import net.minestom.server.color.Color
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.EntityPose
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityTeleportEvent
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import net.minestom.server.item.component.CustomModelData
import net.minestom.server.item.component.SwingAnimation
import net.minestom.server.item.component.UseCooldown
import net.minestom.server.network.ConnectionState
import net.minestom.server.network.packet.server.play.SetCooldownPacket
import net.minestom.server.network.packet.server.play.SetTimePacket
import net.minestom.server.network.player.PlayerSocketConnection

// Shared with gun_animation.glsl: world-age state bands contain a repeating 500-tick
// clock. The shader keeps Minecraft's partial tick, so motion runs at render FPS.
internal const val GUN_ANIMATION_CLOCK_TICKS = 500L
internal const val GUN_FIRE_ANIMATION_TICKS = 2
private const val NATIVE_GUN_EQUIP_TICKS = 7L
internal val GUN_SWING_ANIMATION = SwingAnimation(SwingAnimation.Type.NONE, 6)
internal const val GUN_USE_COOLDOWN_GROUP = "aechronis:gun_use"
internal val GUN_USE_COOLDOWN = UseCooldown(1F, GUN_USE_COOLDOWN_GROUP)
private const val GUN_USE_COOLDOWN_TICKS = 100
private const val GUN_USE_COOLDOWN_REFRESH_TICKS = 20L

internal data class PreparedGunFire(
    val slot: Byte,
    val item: ItemStack,
    val worldAge: Long,
    val ownerClock: SetTimePacket,
)

internal enum class GunAnimationAction(
    val id: Int,
) {
    NONE(0),
    FIRE(1),
    RELOAD(2),
    AIM_IN(3),
    AIM_OUT(4),
    RELOAD_AIMED(5),
    ;

    val isReload: Boolean get() = this == RELOAD || this == RELOAD_AIMED

    fun duration(
        reloadTicks: Int = 1,
        fireTicks: Int = GUN_FIRE_ANIMATION_TICKS,
    ): Int =
        when (this) {
            NONE -> 0
            FIRE -> fireTicks
            AIM_IN, AIM_OUT -> 7
            RELOAD, RELOAD_AIMED -> reloadTicks
        }
}

internal fun gunAnimationPhase(worldAge: Long): Int = Math.floorMod(worldAge, GUN_ANIMATION_CLOCK_TICKS).toInt()

/**
 * Animated guns reserve CMD colors[0]. R carries the action's 9-bit phase low byte;
 * G bit 0 carries its high bit and bits 1..3 its action. Reload uses B as duration.
 * Other actions carry GunMotion.packed in G bits 4..7 and B. White is invalid;
 * an idle zero color is valid. Unrelated CMD fields and later colors are retained.
 * FIRE reserves motion sample codes 20..26 for an ongoing aim transition's age;
 * its movement sample phase is then the shot phase itself.
 */
internal fun gunAnimationTint(
    action: GunAnimationAction,
    worldAge: Long,
    durationTicks: Int,
    motion: GunMotion = GunMotion(),
    fireAimTicks: Int? = null,
): Color {
    require(action == GunAnimationAction.NONE || durationTicks in 1..255)
    require(fireAimTicks == null || action == GunAnimationAction.FIRE && fireAimTicks in 0..6)
    val phase = if (action == GunAnimationAction.NONE) 0 else gunAnimationPhase(worldAge)
    val lowGreen = (phase shr 8) or (action.id shl 1)
    return if (action.isReload) {
        Color(phase and 255, lowGreen, durationTicks)
    } else {
        val packed = if (fireAimTicks == null) motion.packed else (motion.packed and 127) or ((20 + fireAimTicks) shl 7)
        Color(phase and 255, lowGreen or ((packed and 15) shl 4), packed shr 4)
    }
}

internal fun withGunAnimationTint(
    item: ItemStack,
    tint: Color,
): ItemStack {
    val data = item.get(DataComponents.CUSTOM_MODEL_DATA)
    val colors = data?.colors()?.toMutableList() ?: mutableListOf()
    if (colors.isEmpty()) colors.add(tint) else colors[0] = tint
    return item.with(
        DataComponents.CUSTOM_MODEL_DATA,
        CustomModelData(data?.floats() ?: emptyList(), data?.flags() ?: emptyList(), data?.strings() ?: emptyList(), colors),
    )
}

internal fun withoutGunAnimationTint(item: ItemStack): ItemStack {
    val data = item.get(DataComponents.CUSTOM_MODEL_DATA) ?: return item
    val tint = data.colors().firstOrNull() ?: return item
    val action = (tint.green() shr 1) and 7
    val phase = tint.red() or ((tint.green() and 1) shl 8)
    if (phase >= GUN_ANIMATION_CLOCK_TICKS) return item
    if (action == 2 || action == 5) {
        if (tint.green() and 0xF0 != 0 || tint.blue() == 0) return item
    } else if (decodeGunMotion(tint) == null) {
        return item
    }

    // Keep subsequent color indices stable for other model features.
    val colors = data.colors().toMutableList()
    if (colors.size == 1) colors.clear() else colors[0] = Color.WHITE
    return if (data.floats().isEmpty() && data.flags().isEmpty() && data.strings().isEmpty() && colors.isEmpty()) {
        item.without(DataComponents.CUSTOM_MODEL_DATA)
    } else {
        item.with(DataComponents.CUSTOM_MODEL_DATA, CustomModelData(data.floats(), data.flags(), data.strings(), colors))
    }
}

/** Stowed models permit native hotbar animation; live action models suppress metadata-driven swaps. */
internal fun stowedGunItem(
    gun: Gun,
    item: ItemStack,
): ItemStack =
    gunItemModel(
        withoutGunAnimationTint(item),
        gun.material,
        "${if (gun.getAmmo(item) > 0) gun.itemModel else gun.itemModelEmpty}-equip",
    ).with(DataComponents.SWING_ANIMATION, GUN_SWING_ANIMATION)
        .with(DataComponents.USE_COOLDOWN, GUN_USE_COOLDOWN)

private fun activeGunItem(
    gun: Gun,
    item: ItemStack,
): ItemStack {
    val model = item.get(DataComponents.ITEM_MODEL)
    val active =
        if (model == "${gun.itemModel}-equip" || model == "${gun.itemModelEmpty}-equip") {
            gunItemModel(item, gun.material, if (gun.getAmmo(item) > 0) gun.itemModel else gun.itemModelEmpty)
        } else {
            item
        }
    return active.with(DataComponents.SWING_ANIMATION, GUN_SWING_ANIMATION).with(DataComponents.USE_COOLDOWN, GUN_USE_COOLDOWN)
}

/** Owns viewmodel action/motion state; ammunition still belongs to Gun. */
internal object GunAnimation {
    private data class Held(
        val gun: Gun,
        val slot: Byte,
        val instance: Instance,
        val startAge: Long,
        var activated: Boolean = false,
        var lastUseCooldownAge: Long? = null,
    ) {
        val readyAge: Long get() = startAge + NATIVE_GUN_EQUIP_TICKS
    }

    private data class Active(
        val held: Held,
        val startAge: Long,
        val durationTicks: Int,
        val action: GunAnimationAction,
        var tint: Color,
    )

    private data class AimTarget(
        val held: Held,
        val aiming: Boolean,
        val startAge: Long? = null,
    )

    private val held = HashMap<Player, Held>()
    private val active = HashMap<Player, Active>()
    private val aimTargets = HashMap<Player, AimTarget>()

    private fun aimTransition(
        player: Player,
        current: Held,
        age: Long,
    ): AimTarget? =
        aimTargets[player]?.takeIf {
            it.held === current && it.startAge != null && age - it.startAge in 0 until GunAnimationAction.AIM_IN.duration().toLong()
        }

    fun init() {
        Combat.eventNode.addListener(EntityTeleportEvent::class.java) { event ->
            (event.entity as? Player)?.let(::cancel)
        }
    }

    private fun visible(player: Player): Boolean =
        player.isOnline &&
            !player.isDead &&
            player.instance != null &&
            !ModelManager.hasCustomView(player)

    private fun matches(
        player: Player,
        expected: Held,
    ): Boolean =
        player.heldSlot == expected.slot &&
            player.instance === expected.instance &&
            Item.getFromItemStack(player.itemInMainHand) === expected.gun

    private fun clip(
        player: Player,
        gun: Gun,
    ): Active? =
        active[player]?.takeIf {
            it.held.gun === gun &&
                matches(player, it.held) &&
                it.held.instance.worldAge - it.startAge in 0 until it.durationTicks.toLong()
        }

    fun isFiring(
        player: Player,
        gun: Gun,
    ): Boolean = clip(player, gun)?.action == GunAnimationAction.FIRE

    fun isFullyAimed(
        player: Player,
        gun: Gun,
    ): Boolean =
        aimTargets[player]?.let {
            it.held.gun === gun &&
                matches(player, it.held) &&
                it.aiming &&
                (it.startAge == null || it.held.instance.worldAge - it.startAge >= GunAnimationAction.AIM_IN.duration())
        } == true

    fun isSettling(
        player: Player,
        gun: Gun,
    ): Boolean = held[player]?.let { it.gun === gun && matches(player, it) && it.instance.worldAge < it.readyAge } == true

    /** Validate ownership without delaying the client's predicted hotbar selection. */
    fun canUse(
        player: Player,
        gun: Gun,
    ): Boolean {
        if (!visible(player) || Item.getFromItemStack(player.itemInMainHand) !== gun) return false
        ensureHeld(player, gun)
        return !isSettling(player, gun)
    }

    private fun ensureHeld(
        player: Player,
        gun: Gun,
    ): Held {
        val previous = held[player]
        val model = player.itemInMainHand.get(DataComponents.ITEM_MODEL)
        val equipModel = model == "${gun.itemModel}-equip" || model == "${gun.itemModelEmpty}-equip"
        if (previous != null &&
            previous.gun === gun &&
            matches(player, previous) &&
            previous.instance.worldAge >= previous.startAge &&
            !(previous.activated && equipModel)
        ) {
            refreshUseCooldown(player, previous)
            return previous
        }
        // A native slot event usually released the old owner already. Direct inventory
        // changes can also reach here; restore only the old slot, never the new hand.
        if (active.remove(player)?.action?.isReload == true) Combat.reloadTasks.remove(player)?.cancel()
        aimTargets.remove(player)
        GunMotionTracker.clear(player)
        if (previous != null && previous.slot != player.heldSlot) restoreSlot(player, previous.slot.toInt())
        val instance = checkNotNull(player.instance)
        val current = Held(gun, player.heldSlot, instance, instance.worldAge)
        held[player] = current
        refreshUseCooldown(player, current)
        return current
    }

    private fun refreshUseCooldown(
        player: Player,
        current: Held,
    ) {
        val age = current.instance.worldAge
        if (current.lastUseCooldownAge?.let { age - it in 0 until GUN_USE_COOLDOWN_REFRESH_TICKS } == true) return
        // Vanilla still sends use packets on cooldown, but skips crossbow use and
        // its local hand-height reset. Prime this before publishing any ADS model.
        player.sendPacket(SetCooldownPacket(GUN_USE_COOLDOWN_GROUP, GUN_USE_COOLDOWN_TICKS))
        current.lastUseCooldownAge = age
    }

    /** Records the clip without publishing its tint before the matching trail bundle. */
    fun prepareFire(
        player: Player,
        gun: Gun,
        finalItem: ItemStack,
    ): PreparedGunFire? {
        if (!visible(player)) return null
        if (Item.getFromItemStack(player.itemInMainHand) !== gun) return null
        if (player.playerConnection !is PlayerSocketConnection) return null
        if (player.playerConnection.serverState != ConnectionState.PLAY ||
            player.playerConnection.clientState != ConnectionState.PLAY
        ) {
            return null
        }
        val instance = player.instance ?: return null
        val age = instance.worldAge
        val clock = ModelManager.shaderTimePacket(player, age, gun.getAmmo(finalItem) > 0) ?: return null
        val current = ensureHeld(player, gun)
        if (isSettling(player, gun)) return null
        val fireItem =
            if (gun.automatic) {
                // Consecutive FIRE clips can leave no idle tick for updateAim.
                // Accept input at the shot boundary, before capturing both the
                // transition tint and its model direction for the tracer bundle.
                val aiming = Combat.playerAiming[player] == true && gun.hasAmmo(player)
                recordAimTarget(player, current, aiming)
                gunItemModel(
                    finalItem,
                    gun.material,
                    when {
                        aiming -> gun.itemModelAiming
                        gun.getAmmo(finalItem) > 0 -> gun.itemModel
                        else -> gun.itemModelEmpty
                    },
                    aiming = aiming,
                    crouching = player.pose == EntityPose.SNEAKING,
                )
            } else {
                finalItem
            }
        val tint = recordClip(player, current, GunAnimationAction.FIRE, gun.fireAnimationTicks, age)
        current.activated = true
        return PreparedGunFire(player.heldSlot, withGunAnimationTint(activeGunItem(gun, fireItem), tint), age, clock)
    }

    fun start(
        player: Player,
        gun: Gun,
        action: GunAnimationAction,
        durationTicks: Int,
    ) {
        if (action == GunAnimationAction.NONE || !canUse(player, gun)) return
        val selected =
            if (action == GunAnimationAction.RELOAD &&
                aimTargets[player]?.aiming == true
            ) {
                GunAnimationAction.RELOAD_AIMED
            } else {
                action
            }
        startClip(player, gun, selected, durationTicks)
    }

    private fun startClip(
        player: Player,
        gun: Gun,
        action: GunAnimationAction,
        durationTicks: Int,
        startAge: Long? = null,
    ) {
        val instance = player.instance ?: return
        val age = instance.worldAge
        val clipAge = startAge ?: age
        val current = ensureHeld(player, gun)
        val tint = recordClip(player, current, action, durationTicks, age, clipAge)
        publish(player, age, withGunAnimationTint(activeGunItem(gun, player.itemInMainHand), tint))
    }

    private fun recordClip(
        player: Player,
        current: Held,
        action: GunAnimationAction,
        durationTicks: Int,
        age: Long,
        clipAge: Long = age,
    ): Color {
        val motion =
            if (action == GunAnimationAction.FIRE) {
                GunMotionTracker.fireSample(player, age)
            } else {
                GunMotionTracker.sample(player, age, suppress = action.isReload)
            }
        val aimTicks =
            if (action == GunAnimationAction.FIRE) {
                aimTransition(player, current, age)?.startAge?.let { (age - it).toInt() }
            } else {
                null
            }
        val tint = gunAnimationTint(action, clipAge, durationTicks, motion, aimTicks)
        active[player] = Active(current, clipAge, action.duration(durationTicks, current.gun.fireAnimationTicks), action, tint)
        if (action == GunAnimationAction.FIRE && current.gun.sniper && current.gun.fireAnimationTicks > GUN_FIRE_ANIMATION_TICKS) {
            // The captured FIRE tint preserves the shot's incoming ADS transition.
            // The manual bolt cycle then exits to hip; remember that visual target
            // so held aim starts a fresh AIM_IN when the cycle finishes.
            aimTargets[player] = AimTarget(current, aiming = false)
        }
        return tint
    }

    private fun publish(
        player: Player,
        age: Long,
        item: ItemStack,
    ) {
        if (item == player.itemInMainHand) return
        if (!ModelManager.syncShaderTime(player, age)) return
        for (viewer in player.viewers) {
            if (viewer !== player && viewer.isOnline && viewer.instance === player.instance) ModelManager.syncShaderTime(viewer, age)
        }
        held[player]?.activated = true
        player.itemInMainHand = item
    }

    /** Refresh action/motion metadata without changing held-slot selection. */
    fun update(
        player: Player,
        gun: Gun?,
        viewModelVisible: Boolean,
    ) {
        if (!viewModelVisible || !visible(player) || gun == null) {
            if (player in held || player in active) cancel(player)
            return
        }
        ensureHeld(player, gun)
        // Do not touch the selected ItemStack while the client lowers and raises it.
        // The first update switches to a no-swap model and its tint together.
        if (isSettling(player, gun)) return
        var animation = active[player]
        var completedManualCycle = false
        if (animation != null) {
            val elapsed = animation.held.instance.worldAge - animation.startAge
            val changedItem =
                player.itemInMainHand
                    .get(DataComponents.CUSTOM_MODEL_DATA)
                    ?.colors()
                    ?.firstOrNull() != animation.tint
            if (!matches(player, animation.held) || elapsed < 0 || changedItem) {
                cancel(player)
                return
            }
            if ((!animation.action.isReload && elapsed >= animation.durationTicks) ||
                (animation.action.isReload && Combat.reloadTasks[player] == null)
            ) {
                completedManualCycle =
                    animation.action == GunAnimationAction.FIRE &&
                    gun.sniper &&
                    gun.fireAnimationTicks > GUN_FIRE_ANIMATION_TICKS
                val interruptedAim =
                    if (animation.action == GunAnimationAction.FIRE) {
                        aimTransition(player, animation.held, animation.held.instance.worldAge)
                    } else {
                        null
                    }
                val previousTint = animation.tint
                active.remove(player)
                animation =
                    interruptedAim?.let {
                        Active(
                            it.held,
                            checkNotNull(it.startAge),
                            GunAnimationAction.AIM_IN.duration(),
                            if (it.aiming) GunAnimationAction.AIM_IN else GunAnimationAction.AIM_OUT,
                            previousTint,
                        )
                    }
                if (animation != null) active[player] = animation
            }
        }
        val age = checkNotNull(player.instance).worldAge
        val motion =
            GunMotionTracker.sample(
                player,
                age,
                suppress = animation?.action?.isReload == true,
                freeze =
                    animation?.action == GunAnimationAction.FIRE,
            )
        if (animation?.action != GunAnimationAction.FIRE) {
            val tint =
                if (animation == null) {
                    gunAnimationTint(GunAnimationAction.NONE, 0, 0, motion)
                } else {
                    gunAnimationTint(animation.action, animation.startAge, animation.durationTicks, motion)
                }
            animation?.tint = tint
            val item =
                if (completedManualCycle) {
                    gunItemModel(player.itemInMainHand, gun.material, if (gun.hasAmmo(player)) gun.itemModel else gun.itemModelEmpty)
                } else {
                    player.itemInMainHand
                }
            publish(player, age, withGunAnimationTint(activeGunItem(gun, item), tint))
        }
    }

    fun updateAim(
        player: Player,
        gun: Gun,
        aiming: Boolean,
    ) {
        if (isFiring(player, gun) || isSettling(player, gun)) return
        val current = held[player] ?: return
        if (!visible(player) || !matches(player, current)) return
        val target = recordAimTarget(player, current, aiming) ?: return
        val startAge = target.startAge ?: return
        startClip(
            player,
            gun,
            if (aiming) GunAnimationAction.AIM_IN else GunAnimationAction.AIM_OUT,
            GunAnimationAction.AIM_IN.duration(),
            startAge = startAge,
        )
    }

    private fun recordAimTarget(
        player: Player,
        current: Held,
        aiming: Boolean,
    ): AimTarget? {
        val previous = aimTargets[player]
        if (previous?.aiming == aiming) return null
        val transitioning = !(previous == null && !aiming) && active[player]?.action?.isReload != true
        val age = current.instance.worldAge
        val duration = GunAnimationAction.AIM_IN.duration()
        // smoothstep(1 - t) = 1 - smoothstep(t): reverse the remaining clock
        // instead of starting from the opposite endpoint when aim changes.
        val startAge =
            if (transitioning) {
                aimTransition(player, current, age)?.startAge?.let { age - (duration - (age - it)) } ?: age
            } else {
                null
            }
        return AimTarget(current, aiming, startAge).also { aimTargets[player] = it }
    }

    fun cancel(player: Player) {
        if (active[player]?.action?.isReload == true) Combat.reloadTasks.remove(player)?.cancel()
        if (held.remove(player) != null && player.isOnline) player.sendPacket(SetCooldownPacket(GUN_USE_COOLDOWN_GROUP, 0))
        aimTargets.remove(player)
        GunMotionTracker.clear(player)
        active.remove(player)
        // Slot-change listeners run before native selection is committed. Prime the
        // outgoing stack now, including rapid away-and-back input within one tick.
        for (slot in 0 until player.inventory.size) restoreSlot(player, slot)
        val cursor = player.inventory.cursorItem
        val gun = Item.getFromItemStack(cursor) as? Gun
        if (gun != null) {
            val stowed = stowedGunItem(gun, cursor)
            if (stowed != cursor) player.inventory.cursorItem = stowed
        }
    }

    fun restoreSlot(
        player: Player,
        slot: Int,
    ) {
        val item = player.inventory.getItemStack(slot)
        val gun = Item.getFromItemStack(item) as? Gun ?: return
        val stowed = stowedGunItem(gun, item)
        if (stowed != item) player.inventory.setItemStack(slot, stowed)
    }

    fun clear(player: Player) {
        active.remove(player)
        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItemStack(slot)
            if (Item.getFromItemStack(item) !is Gun) continue
            val cleared = withoutGunAnimationTint(item)
            if (cleared != item) player.inventory.setItemStack(slot, cleared)
        }
        val cursor = player.inventory.cursorItem
        if (Item.getFromItemStack(cursor) is Gun) {
            val cleared = withoutGunAnimationTint(cursor)
            if (cleared != cursor) player.inventory.cursorItem = cleared
        }
    }

    fun shutdown() {
        for (player in (held.keys + active.keys).toSet()) cancel(player)
        held.clear()
        active.clear()
        aimTargets.clear()
    }
}
