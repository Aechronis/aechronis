package net.aechronis.server.constants

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

/**
 * Both ships are walkable: the crew stands on a one-cube floor and the hull is not solid, since a
 * solid hull this size exceeds the shulker limit. Numbers are blocks relative to the model centre,
 * with +z as the bow. Seats sit in the middle of the hull, where it is wide enough to sit; flip the signs of z if a model faces the other way.
 */
object Airships {
    private fun title(name: String) = Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)

    /** Gunner seats with a mounted Maxim, one per side: starboard (right) first, then port (left). */
    private fun mountedGunners(
        x: Double,
        y: Double,
        z: Double,
        count: Int,
    ) = listOf(
        VehicleSeat("gun-starboard", "Starboard gunner", VehicleSeatRole.GUNNER, Vec(x, y, z), weaponId = "maxim-starboard"),
        VehicleSeat("gun-port", "Port gunner", VehicleSeatRole.GUNNER, Vec(-x, y, z), weaponId = "maxim-port"),
    ).take(count)

    /** Seated passengers with no mounted gun; they shoot whatever they hold. */
    private fun riders(
        y: Double,
        spots: List<Pair<Double, Double>>,
    ) = spots.mapIndexed { index, (x, z) ->
        VehicleSeat(
            "rider-${index + 1}",
            "Rider ${index + 1}",
            VehicleSeatRole.PASSENGER,
            Vec(x, y, z),
            handheld = true,
        )
    }

    // Model "lz1": 1 unit = 1 block. A 44 block long, 5 wide, 7 tall hull with a keel walkway.
    val zeppelin =
        Airship(
            name = "zeppelin",
            itemName = title("Zeppelin"),
            model = "aechronis:lz1",
            scale = 16.0,
            hitbox = Hitbox(listOf(HitboxPart(offset = Vec.ZERO, size = Vec(2.3, 3.4, 21.9)))),
            health = vehicleHealth(rifleShots = 200, shells = 20, bombs = 10),
            collisionHitbox =
                ShulkerHitbox(
                    (-1..1).flatMap { x ->
                        (-21..21).map { z -> ShulkerHitboxPart(Vec(x.toDouble(), -3.0, z.toDouble()), 1.0) }
                    },
                ),
            seats =
                listOf(VehicleSeat("pilot", "Pilot", VehicleSeatRole.DRIVER, Vec(0.0, -2.2, 6.0))) +
                    mountedGunners(x = 1.2, y = -2.2, z = 0.0, count = 2) +
                    riders(-2.2, listOf(0.9 to -5.0, -0.9 to -5.0, 0.9 to -9.0, -0.9 to -9.0, 0.0 to 3.0)),
            gun = FieldPieces.maximGunWeapon,
            horizontalSpeed = 0.2,
            maxFuel = 20 * COAL_FUEL,
            crashHits = 30,
        )

    // Model "dupuy-de-lome": 1 unit = 0.75 blocks. A 33 block long envelope over a 4 wide, 12 long gondola.
    val airship =
        Airship(
            name = "airship",
            itemName = title("Airship"),
            model = "aechronis:dupuy-de-lome",
            scale = 12.0,
            hitbox =
                Hitbox(
                    listOf(
                        HitboxPart(offset = Vec(0.0, 7.9, 0.0), size = Vec(6.8, 6.4, 16.3)),
                        HitboxPart(offset = Vec(0.0, -10.9, 0.4), size = Vec(1.95, 3.4, 6.0)),
                    ),
                ),
            health = vehicleHealth(rifleShots = 80, shells = 20, bombs = 10),
            collisionHitbox =
                ShulkerHitbox(
                    (-2..2).flatMap { x ->
                        (-4..4).map { z -> ShulkerHitboxPart(Vec(x * 0.75, -12.475, z * 0.75 + 0.4), 0.75) }
                    },
                ),
            seats =
                listOf(VehicleSeat("pilot", "Pilot", VehicleSeatRole.DRIVER, Vec(0.0, -11.8, 2.4))) +
                    mountedGunners(x = 1.3, y = -11.8, z = -1.0, count = 1) +
                    riders(-11.8, listOf(-1.3 to -1.0, 0.0 to -2.8)),
            gun = FieldPieces.maximGunWeapon,
            horizontalSpeed = 0.25,
            maxFuel = 10 * COAL_FUEL,
            crashHits = 12,
        )
}
