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

    // Model "lz1": 1 unit = SIZE blocks, chosen so the two hanging gondola cabins (0.65 units tall) are 2 blocks
    // tall: the hull is then about 132 blocks long. The crew stands on the two cabin platforms (bow and stern),
    // which sit 10 blocks under the model centre. Numbers are model units times SIZE.
    private const val ZEPPELIN_SIZE = 3.0
    private const val ZEPPELIN_FLOOR = -3.22 * ZEPPELIN_SIZE
    private const val BOW_CABIN_Z = 12.0 * ZEPPELIN_SIZE
    private const val STERN_CABIN_Z = -12.3 * ZEPPELIN_SIZE

    // The hull as sliced from the model: blunt nose, long cylinder, tapering tail. Its underside is trimmed
    // 1.9 blocks so the crew can stand on the cabin platforms without being inside it.
    private val ZEPPELIN_HULL =
        listOf(
            HitboxPart(Vec(0.0, 2.25, -61.5), Vec(5.4, 6.75, 4.5)),
            HitboxPart(Vec(0.0, 2.15, -3.0), Vec(6.6, 9.25, 54.0)),
            HitboxPart(Vec(0.0, 1.7, 55.5), Vec(6.3, 8.8, 4.5)),
            HitboxPart(Vec(0.0, 2.25, 63.0), Vec(4.2, 5.55, 3.0)),
        )

    private fun platform(centerZ: Double) = deckFloor(-1.0, 1.0, centerZ - 3.5, centerZ + 3.5, ZEPPELIN_FLOOR, cube = 0.5)

    private fun zeppelinSpot(
        x: Double,
        z: Double,
    ) = Vec(x, ZEPPELIN_FLOOR + 0.3, z)

    val zeppelin =
        Airship(
            name = "zeppelin",
            itemName = title("Zeppelin"),
            model = "aechronis:lz1",
            scale = 16.0 * ZEPPELIN_SIZE,
            hitbox = Hitbox(ZEPPELIN_HULL),
            health = vehicleHealth(rifleShots = 200, shells = 20, bombs = 10),
            collisionHitbox = ShulkerHitbox(platform(BOW_CABIN_Z) + platform(STERN_CABIN_Z)),
            seats =
                listOf(VehicleSeat("pilot", "Pilot", VehicleSeatRole.DRIVER, zeppelinSpot(0.0, BOW_CABIN_Z + 2.4))) +
                    listOf(
                        gunner("starboard", "Starboard gunner", zeppelinSpot(-0.45, BOW_CABIN_Z)),
                        gunner("port", "Port gunner", zeppelinSpot(0.45, BOW_CABIN_Z - 1.0)),
                    ) +
                    riders(
                        zeppelinSpot(0.0, STERN_CABIN_Z + 2.4),
                        zeppelinSpot(0.0, STERN_CABIN_Z + 1.2),
                        zeppelinSpot(0.0, STERN_CABIN_Z),
                        zeppelinSpot(0.0, STERN_CABIN_Z - 1.2),
                        zeppelinSpot(0.0, STERN_CABIN_Z - 2.4),
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
                    ellipsoid(Vec(0.0, 7.6, 0.0), Vec(7.5, 7.2, 16.5), slices = 9) +
                        HitboxPart(offset = Vec(0.0, -10.9, 0.4), size = Vec(1.95, 3.4, 6.0)),
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
