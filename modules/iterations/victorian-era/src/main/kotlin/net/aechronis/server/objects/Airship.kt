package net.aechronis.server.objects

import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.ShulkerHitbox
import net.aechronis.combat.objects.VehicleSeat
import net.kyori.adventure.text.Component
import net.minestom.server.entity.Player

/**
 * Big turning balloon with a walkable deck. Anyone can stand on the deck; the gunner seats
 * fire [gun] (jump key) wherever the gunner looks.
 */
class Airship(
    name: String,
    itemName: Component,
    model: String,
    scale: Double,
    hitbox: Hitbox,
    health: Health,
    collisionHitbox: ShulkerHitbox,
    seats: List<VehicleSeat>,
    val gun: Gun,
    horizontalSpeed: Double,
    maxFuel: Int,
    crashHits: Int,
    turnSpeed: Float = 1f,
) : Balloon(
        name = name,
        itemName = itemName,
        itemModel = model,
        model = model,
        scale = scale,
        hitbox = hitbox,
        health = health,
        horizontalSpeed = horizontalSpeed,
        turnSpeed = turnSpeed,
        maxFuel = maxFuel,
        crashHits = crashHits,
        seats = seats,
        // the crew stays visible and shootable, the deck is a place to fight from
        invisibleWhileRiding = false,
        invulnerableWhileRiding = false,
        collisionHitbox = collisionHitbox,
    ) {
    override fun onGunnerTick(player: Player) {
        super.onGunnerTick(player)
        if (KeyPressListener.playerInputEvent[player]?.isHoldingJumpKey != true) return
        gun.fire(player, player.position.add(0.0, player.eyeHeight, 0.0), ignoreAmmo = true, lagCompensate = false)
    }
}
