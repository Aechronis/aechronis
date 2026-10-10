package net.aechronis.server.constants

import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.aechronis.combat.objects.ShulkerHitbox
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
 * with +z as the bow, so starboard (right) is -x. They come from the seat and floor markers in the
 * Blockbench files: blocks = (model units - 8) * scale / 16.
 */
object Airships {
    private fun title(name: String) = Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)

    private fun gunner(
        id: String,
        name: String,
        at: Vec,
    ) = VehicleSeat(id, name, VehicleSeatRole.GUNNER, at, weaponId = "maxim-$id")

    /** Seated passengers with no mounted gun; they shoot whatever they hold. */
    private fun riders(vararg at: Vec) =
        at.mapIndexed { index, spot ->
            VehicleSeat("rider-${index + 1}", "Rider ${index + 1}", VehicleSeatRole.PASSENGER, spot, handheld = true)
        }

    // Model "lz1": 1 unit = 1 block. A 44 block long hull with no cabin; the crew walks inside it.
    private const val ZEPPELIN_FLOOR = -2.1
    private const val ZEPPELIN_SEAT = -1.8

    val zeppelin =
        Airship(
            name = "zeppelin",
            itemName = title("Zeppelin"),
            model = "aechronis:lz1",
            scale = 16.0,
            hitbox = Hitbox(listOf(HitboxPart(offset = Vec.ZERO, size = Vec(2.3, 3.4, 21.9)))),
            health = vehicleHealth(rifleShots = 200, shells = 20, bombs = 10),
            collisionHitbox = ShulkerHitbox(deckFloor(-1.5, 1.5, -21.5, 21.5, ZEPPELIN_FLOOR, cube = 1.0)),
            seats =
                listOf(VehicleSeat("pilot", "Pilot", VehicleSeatRole.DRIVER, Vec(0.0, ZEPPELIN_SEAT, 6.0))) +
                    listOf(
                        gunner("starboard", "Starboard gunner", Vec(-1.2, ZEPPELIN_SEAT, 0.0)),
                        gunner("port", "Port gunner", Vec(1.2, ZEPPELIN_SEAT, 0.0)),
                    ) +
                    riders(
                        Vec(-0.9, ZEPPELIN_SEAT, -5.0),
                        Vec(0.9, ZEPPELIN_SEAT, -5.0),
                        Vec(-0.9, ZEPPELIN_SEAT, -9.0),
                        Vec(0.9, ZEPPELIN_SEAT, -9.0),
                        Vec(0.0, ZEPPELIN_SEAT, 3.0),
                    ),
            gun = FieldPieces.maximGunWeapon,
            horizontalSpeed = 0.2,
            maxFuel = 20 * COAL_FUEL,
            crashHits = 30,
        )

    // Model "dupuy-de-lome": 1 unit = 0.75 blocks. A 33 block long envelope over a small wooden gondola.
    private const val AIRSHIP_FLOOR = -11.77
    private const val AIRSHIP_SEAT = -11.48

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
            collisionHitbox = ShulkerHitbox(deckFloor(-1.425, 1.425, -2.775, 2.775, AIRSHIP_FLOOR, cube = 0.75)),
            seats =
                listOf(VehicleSeat("pilot", "Pilot", VehicleSeatRole.DRIVER, Vec(0.0, AIRSHIP_SEAT, 1.5))) +
                    listOf(gunner("starboard", "Starboard gunner", Vec(-0.825, AIRSHIP_SEAT, 0.0))) +
                    riders(Vec(0.825, AIRSHIP_SEAT, 0.0), Vec(0.0, AIRSHIP_SEAT, -1.5)),
            gun = FieldPieces.maximGunWeapon,
            horizontalSpeed = 0.25,
            maxFuel = 10 * COAL_FUEL,
            crashHits = 12,
        )
}
