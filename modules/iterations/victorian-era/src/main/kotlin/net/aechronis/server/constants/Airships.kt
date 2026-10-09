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
 * with +z as the bow; flip the signs of z if a model faces the other way.
 */
object Airships {
    private fun title(name: String) = Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)

    private fun gunnerSeats(
        x: Double,
        y: Double,
        count: Int,
    ) = listOf(
        VehicleSeat("gun-starboard", "Starboard gunner", VehicleSeatRole.GUNNER, Vec(x, y, 0.0), weaponId = "maxim-starboard"),
        VehicleSeat("gun-port", "Port gunner", VehicleSeatRole.GUNNER, Vec(-x, y, 0.0), weaponId = "maxim-port"),
    ).take(count)

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
                listOf(VehicleSeat("pilot", "Pilot", VehicleSeatRole.DRIVER, Vec(0.0, -2.45, 18.0), standing = true)) +
                    gunnerSeats(x = 1.2, y = -2.2, count = 2),
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
                ShulkerHitbox((-2..2).flatMap { x -> (-7..8).map { z -> ShulkerHitboxPart(Vec(x * 0.75, -14.0, z * 0.75), 0.75) } }),
            seats =
                listOf(VehicleSeat("pilot", "Pilot", VehicleSeatRole.DRIVER, Vec(0.0, -13.6, 5.0), standing = true)) +
                    gunnerSeats(x = 1.4, y = -13.3, count = 1),
            gun = FieldPieces.maximGunWeapon,
            horizontalSpeed = 0.25,
            maxFuel = 10 * COAL_FUEL,
            crashHits = 12,
        )
}
