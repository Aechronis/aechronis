package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.aechronis.combat.objects.ShulkerHitbox
import net.aechronis.combat.objects.ShulkerHitboxPart
import net.aechronis.combat.objects.VehicleSeat
import net.aechronis.combat.objects.VehicleSeatRole
import net.aechronis.server.objects.Airship
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Vec

object Airships {
    /**
     * Both use the placeholder "zeppelin" model: 16 pixels long at scale 16 = 16 blocks, envelope above,
     * a flat deck below. Layout numbers are in blocks at scale 16 and shrink with [scale].
     */
    private fun build(
        name: String,
        title: String,
        scale: Double,
        hp: Float,
        gunners: Int,
        speed: Double,
        coal: Int,
        crashHits: Int,
    ): Airship {
        val s = scale / 16.0
        val envelope = HitboxPart(offset = Vec(0.0, 3.5 * s, 0.0), size = Vec(3.0 * s, 3.5 * s, 8.0 * s))
        val deck = HitboxPart(offset = Vec(0.0, -4.5 * s, 0.0), size = Vec(2.5 * s, 0.5 * s, 6.0 * s))
        // The envelope sits 4 blocks above the deck so jumping never hits it; the crew walks on a one-cube floor.
        val floor =
            (-2..2).flatMap { x -> (-5..5).map { z -> ShulkerHitboxPart(Vec(x * s, -4.5 * s, z * s), s) } }
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
            seats = seats(s, gunners),
            gun = FieldPieces.maximGunWeapon,
            horizontalSpeed = speed,
            maxFuel = coal * COAL_FUEL,
            crashHits = crashHits,
        )
    }

    /** A standing pilot at the bow and Maxim gunners at the sides; everyone else just stands on the deck. */
    private fun seats(
        s: Double,
        gunners: Int,
    ): List<VehicleSeat> =
        listOf(VehicleSeat("pilot", "Pilot", VehicleSeatRole.DRIVER, Vec(0.0, -3.95 * s, 5.0 * s), standing = true)) +
            listOf(
                VehicleSeat(
                    "gun-starboard",
                    "Starboard gunner",
                    VehicleSeatRole.GUNNER,
                    Vec(2.0 * s, -3.7 * s, 0.0),
                    weaponId = "maxim-starboard",
                ),
                VehicleSeat("gun-port", "Port gunner", VehicleSeatRole.GUNNER, Vec(-2.0 * s, -3.7 * s, 0.0), weaponId = "maxim-port"),
            ).take(gunners)

    // 200 rifle shots
    val zeppelin =
        build("zeppelin", "Zeppelin", scale = 16.0, hp = 200 * RIFLE_HIT, gunners = 2, speed = 0.2, coal = 20, crashHits = 30)

    // small cheaper cousin: 80 rifle shots, one gunner
    val airship =
        build("airship", "Airship", scale = 10.0, hp = 80 * RIFLE_HIT, gunners = 1, speed = 0.25, coal = 10, crashHits = 12)
}
