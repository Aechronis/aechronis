package net.aechronis.combat.utils

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.TurretScope
import net.aechronis.combat.objects.VehicleSeatHotbar
import net.aechronis.combat.storage.GunZoomPreferences
import net.aechronis.combat.tasks.ModelManager
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.network.packet.server.play.SetPlayerInventorySlotPacket
import java.util.concurrent.ConcurrentHashMap

/**
 * Zoom while aiming a gun: aiming eases in to the player's minimum zoom, scrolling forward eases
 * straight to their maximum and scrolling back eases straight to the minimum. Releasing aim eases
 * back out. The zoom itself is shader-side: ModelManager sends a world-time band and
 * minecraft:projection.glsl scales the world projection.
 *
 * Each ease runs over [EASE_TICKS] ticks of the client's own clock, so it stays smooth at any frame
 * rate. Every (from, to) pair has its own band, and an ease only starts on a world age divisible by
 * [TRANSITION_TICKS]: the gun animation phase (world age mod 500) stays intact, and the shader reads
 * the progress as that phase mod [TRANSITION_TICKS]. The server shows the end band as soon as the
 * ease is done; the remaining ticks cover that packet arriving late.
 *
 * The client moves its hotbar selection before the server sees a scroll, and refusing the change
 * snaps the selection back. So while aiming, every other hotbar slot shows a client-side copy of
 * the gun, the shader hides the hotbar, and the real gun follows each scroll into the newly
 * selected slot. When aiming stops the gun goes back to its own slot and the real hotbar returns.
 */
internal object GunZoom {
    /** Zoom per level, also the min/max choices; level 0 is 1x. Keep in sync with AECHRONIS_ZOOMS in projection.glsl. */
    val LEVELS = listOf("1x", "1.5x", "2x", "3x", "4x", "6x")

    // Aiming at 1x (min zoom 1x, or reloading): combat HUD with the hotbar hidden. Below the aiming band.
    private const val SESSION_BAND = 8000L

    // Bands, in sync with projection.glsl and the HUD shaders: holding level n at 19000 + (n - 1) * 1000;
    // easing pair i (500 ticks each) at 0 + i * 500 for i < 16, else 12000 + (i - 16) * 500.
    // All of them count as aiming. The drone telemetry decoder also reads 0..23999, but only in a
    // drone camera, where GunZoom is off.
    private const val FIRST_ZOOM_BAND = 19000L
    private const val LOW_TRANSITION_PAIRS = 16
    private const val HIGH_TRANSITION_BAND = 12000L
    private const val TRANSITION_TICKS = 5L
    private const val EASE_TICKS = 4L // keep in sync with the ease-out in projection.glsl
    private const val SNIPER_SCOPE_LEVEL = 2 // 2x

    /** An ease from level [from] to [to]; it waits for an aligned world age to set [start]. */
    private class Transition(
        val from: Int,
        var to: Int,
    ) {
        var start: Long? = null
    }

    private class Session(
        val home: Int,
    ) {
        var current = home

        /** The level the player picked; [shown] is the level on screen, which eases towards it. */
        var level = 0
        var shown = 0
        var transition: Transition? = null
        var fake: ItemStack? = null
        var realHotbar: List<ItemStack> = emptyList()
        var followed: PlayerChangeHeldSlotEvent? = null
        var lastStep = 0
        var lastScrollAt = 0L
    }

    // Scroll reports closer together than this belong to one spin of the wheel.
    private const val SPIN_GAP_MS = 200L

    /**
     * Zoom direction for a hotbar move of [distance] slots to the right (0..8); positive zooms in.
     * Wheel up selects the previous slot and zooms in. The client reports its selection once per
     * tick, so a fast scroll arrives as a jump of several slots, indistinguishable from a number
     * key. A jump of 5+ slots read the shortest way round the ring comes out reversed, so during a
     * spin ([last] is the previous step, 0 when this report starts a new spin) jumps of 2+ slots
     * keep the spin's direction. A new spin is read the shortest way round.
     */
    fun scrollStep(
        distance: Int,
        last: Int,
    ): Int {
        val shortest = if (distance > 4) 9 - distance else -distance
        if (last == 0 || shortest in -1..1) return shortest
        return if (last > 0) 9 - distance else -distance
    }

    private val sessions = ConcurrentHashMap<Player, Session>()

    // Ease-outs still running after their aim session ended.
    private val aimOuts = ConcurrentHashMap<Player, Transition>()

    /** Starts or ends the aim session and moves the zoom; call once per tick before the band is chosen. */
    fun tick(player: Player) {
        val age = player.instance?.worldAge ?: return
        val gun = aimedGun(player)
        val customView = ModelManager.hasCustomView(player)
        val active =
            gun != null &&
                GunZoomPreferences.get(player.uuid).enabled &&
                !customView &&
                // A scope raises first, so the fake hotbar never shows during its aim-in.
                (!gun.sniper || GunAnimation.isFullyAimed(player, gun))
        aimOuts[player]?.let { if (customView || advance(it, age)) aimOuts.remove(player) }
        val session = sessions[player]
        if (active && session == null) {
            aimOuts.remove(player)
            sessions[player] = Session(player.heldSlot.toInt()).also { it.level = minLevel(player, gun!!) }
        } else if (!active && session != null) {
            end(player, restoreSelection = true)
        }
        sessions[player]?.let { step(it, age, reloading = Combat.playerStates[player]?.reloadTask != null) }
    }

    // Eases the shown level straight to the picked one (1x while reloading), one aligned ease at a time.
    private fun step(
        session: Session,
        age: Long,
        reloading: Boolean,
    ) {
        val target = if (reloading) 0 else session.level
        session.transition?.let {
            if (it.start == null) {
                it.to = target
            } else if (advance(it, age)) {
                session.shown = it.to
                session.transition = null
            }
        }
        if (session.transition == null && target != session.shown) {
            session.transition = Transition(session.shown, target)
        }
        session.transition?.let {
            if (it.from == it.to) session.transition = null else advance(it, age)
        }
    }

    // Starts [transition] on an aligned world age; true once its ease is done.
    private fun advance(
        transition: Transition,
        age: Long,
    ): Boolean {
        val start = transition.start
        if (start == null) {
            if (age % TRANSITION_TICKS == 0L) transition.start = age
            return false
        }
        return age - start >= EASE_TICKS
    }

    /** Keeps the other hotbar slots showing the current gun stack; call after the held model update. */
    fun syncFakes(player: Player) {
        val session = sessions[player] ?: return
        val item = player.itemInMainHand
        val hotbar = (0..8).map(player.inventory::getItemStack)
        if (item == session.fake && hotbar == session.realHotbar) return
        session.fake = item
        session.realHotbar = hotbar
        for (slot in 0..8) {
            if (slot != player.heldSlot.toInt()) player.sendPacket(SetPlayerInventorySlotPacket(slot, item))
        }
    }

    /**
     * Handles a hotbar change during an aim session. It picks the maximum or minimum zoom and moves
     * the gun into the selected slot, leaving the event uncancelled so the client keeps its selection.
     * Returns true when consumed; other listeners skip it through [isFollowing]. Number keys count as
     * scrolls too.
     */
    fun onScroll(event: PlayerChangeHeldSlotEvent): Boolean {
        if (event.isCancelled || event.oldSlot == event.newSlot) return false
        val player = event.player
        val session = sessions[player] ?: return false
        val now = System.currentTimeMillis()
        val last = if (now - session.lastScrollAt <= SPIN_GAP_MS) session.lastStep else 0
        val step = scrollStep(Math.floorMod(event.newSlot - event.oldSlot, 9), last)
        session.lastStep = step
        session.lastScrollAt = now
        val gun = aimedGun(player)
        if (gun == null || session.current != event.oldSlot.toInt()) {
            end(player, restoreSelection = false)
            return false
        }
        if (step != 0 && Combat.playerStates[player]?.reloadTask == null) {
            session.level = if (step > 0) maxLevel(player, gun) else minLevel(player, gun)
        }
        move(player, session, event.newSlot.toInt())
        session.followed = event
        return true
    }

    /** True for the scroll [onScroll] just consumed, so weapon-switch handling ignores it. */
    fun isFollowing(event: PlayerChangeHeldSlotEvent): Boolean = sessions[event.player]?.followed === event

    /** The gun's own hotbar slot while it follows the scroll wheel. */
    fun homeSlot(player: Player): Byte? = sessions[player]?.home?.toByte()

    /** The shader band for this tick, or null to leave the band to ModelManager. */
    fun band(player: Player): Long? {
        aimOuts[player]?.let { return transitionBand(it) }
        val gun = aimedGun(player) ?: return null
        val session = sessions[player]
        if (session == null) {
            if (Combat.playerStates[player]?.reloadTask != null) return null
            return floor(player, gun).takeIf { it > 0 }?.let(::levelBand)
        }
        return session.transition?.let(::transitionBand) ?: levelBand(session.shown)
    }

    private fun levelBand(level: Int): Long = if (level == 0) SESSION_BAND else FIRST_ZOOM_BAND + (level - 1) * 1000L

    // Until it starts, an ease keeps showing its start level. Pair i = from * 5 + to, skipping to == from;
    // keep in sync with aechronis_zoom_factor.
    private fun transitionBand(transition: Transition): Long {
        val from = transition.from
        val to = transition.to
        if (transition.start == null) return levelBand(from)
        val pair = from * (LEVELS.size - 1) + if (to < from) to else to - 1
        return if (pair < LOW_TRANSITION_PAIRS) pair * 500L else HIGH_TRANSITION_BAND + (pair - LOW_TRANSITION_PAIRS) * 500L
    }

    /** Ends the session and restores the real hotbar. Only sends packets to online players. */
    fun clear(player: Player) {
        end(player, restoreSelection = player.isOnline)
        aimOuts.remove(player)
    }

    fun clearAll() = (sessions.keys + aimOuts.keys).toList().forEach(::clear)

    private fun end(
        player: Player,
        restoreSelection: Boolean,
    ) {
        val session = sessions.remove(player) ?: return
        // Ease back out from the level on screen, or the one it was easing towards (aiming bands, so the
        // hotbar and crosshair return once it is done).
        val shown = session.transition?.takeIf { it.start != null }?.to ?: session.shown
        if (shown > 0) aimOuts[player] = Transition(shown, 0)
        move(player, session, session.home)
        if (!player.isOnline) return
        player.inventory.update()
        if (restoreSelection && player.heldSlot.toInt() != session.home) player.setHeldItemSlot(session.home.toByte())
    }

    // Moves the gun to [slot] without packets (the client already shows the gun there). Only the gun and
    // the item from its current slot are ever out of place: that item waits in the home slot.
    private fun move(
        player: Player,
        session: Session,
        slot: Int,
    ) {
        if (session.current == slot) return
        swap(player, session.current, session.home)
        swap(player, session.home, slot)
        session.current = slot
        session.realHotbar = emptyList()
        GunAnimation.moveHeld(player, slot.toByte())
    }

    private fun swap(
        player: Player,
        first: Int,
        second: Int,
    ) {
        if (first == second) return
        val inventory = player.inventory
        val item = inventory.getItemStack(first)
        inventory.setItemStack(first, inventory.getItemStack(second), false)
        inventory.setItemStack(second, item, false)
    }

    private fun aimedGun(player: Player): Gun? {
        if (VehicleSeatHotbar.isActive(player) || TurretScope.isActive(player)) return null
        if (Combat.playerStates[player]?.aiming != true) return null
        return Item.getFromItemStack(player.itemInMainHand) as? Gun
    }

    // Aiming starts at the player's minimum zoom, at least the scope's.
    private fun minLevel(
        player: Player,
        gun: Gun,
    ): Int = maxOf(GunZoomPreferences.get(player.uuid).minSlot.coerceIn(LEVELS.indices), floor(player, gun))

    private fun maxLevel(
        player: Player,
        gun: Gun,
    ): Int = maxOf(GunZoomPreferences.get(player.uuid).maxSlot.coerceIn(LEVELS.indices), minLevel(player, gun))

    // A fully raised sniper scope starts at 2x, which used to come from the FOV effect.
    private fun floor(
        player: Player,
        gun: Gun,
    ): Int = if (gun.sniper && GunAnimation.isFullyAimed(player, gun)) SNIPER_SCOPE_LEVEL else 0
}
