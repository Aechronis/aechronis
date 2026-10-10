package net.aechronis.server.objects

import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.objects.Ammo
import net.aechronis.combat.objects.ArmedVehicle
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.ShulkerHitbox
import net.aechronis.combat.objects.VehicleSeat
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

/**
 * Big turning balloon with a walkable deck. Anyone can stand on the deck. Gunner seats fire [gun]
 * (jump key) wherever the gunner looks, drawing on one shared magazine that reloads from the
 * gunner's belts; passenger seats keep the rider's own hotbar and guns.
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
    /** How far the model hangs below the hitbox, so the hull does not spawn partly in the ground. */
    private val groundClearance: Double = 0.0,
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
    ),
    ArmedVehicle {
    override fun spawn(
        instance: Instance,
        pos: Pos,
    ): Entity = super.spawn(instance, pos.add(0.0, groundClearance, 0.0))

    override val ammo: Ammo = gun.ammo
    override val maxAmmo: Int = gun.maxAmmo
    override val reloadTime: Long = gun.reloadTime

    override fun onGunnerTick(player: Player) {
        super.onGunnerTick(player)
        if (KeyPressListener.playerInputEvent[player]?.isHoldingJumpKey != true) return
        val ship = entityOf(player) ?: return
        if (!hasReadyAmmo(player, ship)) return
        // the ship and everyone aboard must not block the bullets
        val ignored = occupants(ship).toSet() + ship
        val eye = player.position.add(0.0, player.eyeHeight, 0.0)
        if (gun.fire(player, eye, ignoreAmmo = true, lagCompensate = false, ignoredEntities = ignored)) consumeAmmo(ship)
    }
}
