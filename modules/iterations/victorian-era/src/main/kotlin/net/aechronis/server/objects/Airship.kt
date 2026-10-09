package net.aechronis.server.objects

import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.objects.AnimatedPart
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.ShulkerHitbox
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Big turning balloon with a walkable deck. The first [gunnerCount] passengers are gunners
 * and fire [gun] (jump key) wherever they look.
 */
class Airship(
    name: String,
    itemName: Component,
    scale: Double,
    hitbox: Hitbox,
    health: Health,
    collisionHitbox: ShulkerHitbox,
    seatOffsets: List<Vec>,
    val gun: Gun,
    val gunnerCount: Int = 2,
    horizontalSpeed: Double = 0.2,
    turnSpeed: Float = 1f,
    maxFuel: Int = 0,
    crashHits: Int = 3,
    animatedParts: List<AnimatedPart> = emptyList(),
) : Balloon(
        name = name,
        itemName = itemName,
        scale = scale,
        hitbox = hitbox,
        health = health,
        horizontalSpeed = horizontalSpeed,
        turnSpeed = turnSpeed,
        maxFuel = maxFuel,
        crashHits = crashHits,
        seatOffsets = seatOffsets,
        // gunners and passengers stay visible and shootable, the deck is a place to fight from
        invisibleWhileRiding = false,
        invulnerableWhileRiding = false,
        animatedParts = animatedParts,
        collisionHitbox = collisionHitbox,
    ) {
    override fun onTick(player: Player) {
        driverEntity(player)?.let(::fireGunners)
        super.onTick(player)
    }

    // dismount onto the deck instead of beside the hull
    override fun onExit(player: Player) = stayAboard(player) { super.onExit(player) }

    override fun onPassengerExit(player: Player) = stayAboard(player) { super.onPassengerExit(player) }

    private fun stayAboard(
        player: Player,
        exit: () -> Unit,
    ) {
        val seat = player.position
        val instance = player.instance
        exit()
        if (instance != null && player.instance === instance) player.teleport(seat.add(0.0, 0.1, 0.0))
    }

    override fun onUnoccupiedTick(entity: Entity) {
        fireGunners(entity)
        super.onUnoccupiedTick(entity)
    }

    /** Carries players standing on the deck along with the hull's translation and turn. */
    override fun onMoved(
        entity: Entity,
        from: Pos,
        to: Pos,
    ) {
        val s = scale / 16.0
        val deckTop = -4.0 * s
        val turn = to.yaw - from.yaw
        for (player in entity.instance?.players ?: return) {
            if (isVehicleOccupant(player)) continue
            val local = rotate(Vec(player.position.x - from.x, player.position.y - from.y, player.position.z - from.z), -from.yaw)
            if (abs(local.x) > 2.5 * s || abs(local.z) > 6.0 * s || local.y < deckTop - 0.5 || local.y > deckTop + 3.0) continue
            val carried = rotate(local, to.yaw)
            player.teleport(Pos(to.x + carried.x, to.y + carried.y, to.z + carried.z, player.position.yaw + turn, player.position.pitch))
        }
    }

    private fun rotate(
        v: Vec,
        yaw: Float,
    ): Vec {
        val r = Math.toRadians(yaw.toDouble())
        return Vec(v.x * cos(r) - v.z * sin(r), v.y, v.x * sin(r) + v.z * cos(r))
    }

    private fun fireGunners(entity: Entity) {
        for (gunner in passengerPlayers(entity).take(gunnerCount)) {
            if (KeyPressListener.playerInputEvent[gunner]?.isHoldingJumpKey != true) continue
            gun.fire(gunner, gunner.position.add(0.0, gunner.eyeHeight, 0.0), ignoreAmmo = true, lagCompensate = false)
        }
    }
}
