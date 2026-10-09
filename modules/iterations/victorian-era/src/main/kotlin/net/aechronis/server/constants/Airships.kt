package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.aechronis.combat.objects.ShulkerHitbox
import net.aechronis.combat.objects.ShulkerHitboxPart
import net.aechronis.server.objects.Airship
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Vec

object Airships {
    // vehicle damage is flat per hit by ammo type; Victorian rifles do 44-50, so 47 on average
    private const val RIFLE_HIT = 47F

    // ticks of flight per piece of coal (45 seconds), matching Balloon.coalFuel
    private const val COAL_FUEL = 900

    /**
     * Both use the placeholder "zeppelin" model: 16 pixels long at scale 16 = 16 blocks, envelope above,
     * a flat deck below. Layout numbers are in blocks at scale 16 and shrink with [scale].
     */
    private fun build(
        name: String,
        title: String,
        scale: Double,
        hp: Float,
        seats: Int,
        gunners: Int,
        speed: Double,
        coal: Int,
        crashHits: Int,
    ): Airship {
        val s = scale / 16.0
        val envelope = HitboxPart(offset = Vec(0.0, 3.5 * s, 0.0), size = Vec(3.0 * s, 3.5 * s, 8.0 * s))
        val deck = HitboxPart(offset = Vec(0.0, -4.5 * s, 0.0), size = Vec(2.5 * s, 0.5 * s, 6.0 * s))
        // envelope sits 4 blocks above the deck so jumping on it never hits the hull; solid envelope plus a one-cube-thick floor the crew walks on
        val floor =
            (-2..2).flatMap { x -> (-5..5).map { z -> ShulkerHitboxPart(Vec(x * s, -4.5 * s, z * s), s) } }
        val y = -3.7 * s
        return Airship(
            name = name,
            itemName = Component.text(title, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            scale = scale,
            hitbox = Hitbox(listOf(envelope, deck)),
            health =
                Health(
                    hp,
                    mapOf(
                        AmmoTypes.NORMAL to RIFLE_HIT,
                        AmmoTypes.EXPLOSIVE to hp / 20,
                        AmmoTypes.BOMB to hp / 10,
                        AmmoTypes.MISSILE to hp / 10,
                    ),
                ),
            collisionHitbox = ShulkerHitbox(ShulkerHitbox.fromHitbox(Hitbox(listOf(envelope))).parts + floor),
            // pilot, two gunner positions, then passengers
            seatOffsets =
                listOf(
                    Vec(0.0, y, 5.0 * s),
                    Vec(2.0 * s, y, 0.0),
                    Vec(-2.0 * s, y, 0.0),
                    Vec(s, y, -2.0 * s),
                    Vec(-s, y, -2.0 * s),
                    Vec(s, y, -4.0 * s),
                    Vec(-s, y, -4.0 * s),
                    Vec(0.0, y, 3.0 * s),
                ).take(seats),
            gun = FieldPieces.gatlingGunWeapon,
            gunnerCount = gunners,
            horizontalSpeed = speed,
            maxFuel = coal * COAL_FUEL,
            crashHits = crashHits,
        )
    }

    // 200 rifle shots
    val zeppelin =
        build("zeppelin", "Zeppelin", scale = 16.0, hp = 200 * RIFLE_HIT, seats = 8, gunners = 2, speed = 0.2, coal = 20, crashHits = 30)

    // small cheaper cousin: 80 rifle shots, one gunner
    val airship =
        build("airship", "Airship", scale = 10.0, hp = 80 * RIFLE_HIT, seats = 4, gunners = 1, speed = 0.25, coal = 10, crashHits = 12)
}
