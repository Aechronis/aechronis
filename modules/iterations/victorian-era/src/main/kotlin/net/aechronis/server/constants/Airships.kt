package net.aechronis.server.constants

import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.aechronis.combat.objects.ShulkerHitbox
import net.aechronis.combat.objects.ShulkerHitboxPart
import net.aechronis.combat.objects.VehicleSeat
import net.aechronis.combat.objects.VehicleSeatRole
import net.aechronis.server.objects.Airship
import net.minestom.server.coordinate.Vec

/**
 * Both ships are walkable: the crew stands on small platforms and the hull is not solid, since a
 * solid hull this size exceeds the shulker limit. Numbers are blocks relative to the model centre,
 * with +z as the bow, so starboard (right) is -x: blocks = (model units - 8) * scale / 16.
 */
object Airships {
    val zeppelin =
        Airship(
            name = "zeppelin",
            itemName = vehicleTitle("Zeppelin"),
            model = "aechronis:lz1",
            scale = Zeppelin.SCALE,
            hitbox = Hitbox(Zeppelin.hull),
            health = vehicleHealth(rifleShots = 200, shells = 20, bombs = 10),
            collisionHitbox = ShulkerHitbox(Zeppelin.platforms + Zeppelin.rails),
            seats = Zeppelin.seats,
            gun = FieldPieces.maximGunWeapon,
            horizontalSpeed = 0.2,
            groundClearance = Zeppelin.GROUND_CLEARANCE,
        )

    val airship =
        Airship(
            name = "airship",
            itemName = vehicleTitle("Airship"),
            model = "aechronis:dupuy-de-lome",
            scale = Dirigible.SCALE,
            hitbox = Hitbox(Dirigible.hull),
            health = vehicleHealth(rifleShots = 80, shells = 20, bombs = 10),
            collisionHitbox = ShulkerHitbox(Dirigible.platform + Dirigible.rails),
            seats = Dirigible.seats,
            gun = FieldPieces.maximGunWeapon,
            horizontalSpeed = 0.25,
            groundClearance = Dirigible.GROUND_CLEARANCE,
        )

    /**
     * Model "lz1": 1 unit = 3 blocks, so the two hanging gondola cabins (0.65 units tall) are 2 blocks tall.
     * The model's middle stretch is cut out, leaving a hull about 82 blocks long. The crew stands on a
     * platform in each cabin. Only the pilot and gunners have seats, at the bow; everyone else stands on the platforms.
     */
    private object Zeppelin {
        const val SCALE = 16.0 * 3.0
        const val GROUND_CLEARANCE = 3.5

        private const val FLOOR = -9.66
        private const val BOW_CABIN_Z = 11.355
        private const val STERN_CABIN_Z = -12.255
        private const val BOARDWALK_TOP = FLOOR + 1.0

        /** Blunt nose, long cylinder, tapering tail. The underside is trimmed 1.9 blocks so the crew can stand under it. */
        val hull =
            listOf(
                HitboxPart(Vec(0.0, 2.25, -36.85), Vec(5.4, 6.75, 4.5)),
                HitboxPart(Vec(0.0, 2.15, -3.0), Vec(6.6, 9.25, 29.35)),
                HitboxPart(Vec(0.0, 1.7, 30.85), Vec(6.3, 8.8, 4.5)),
                HitboxPart(Vec(0.0, 2.25, 38.35), Vec(4.2, 5.55, 3.0)),
            )

        val platforms = cabinPlatform(BOW_CABIN_Z) + cabinPlatform(STERN_CABIN_Z) + boardwalk()
        val rails = cabinRails(BOW_CABIN_Z) + cabinRails(STERN_CABIN_Z) + boardwalkRails()

        val seats =
            listOf(
                VehicleSeat("pilot", "Pilot", VehicleSeatRole.DRIVER, seatAt(0.0, BOW_CABIN_Z + 2.4)),
                maximSeat("starboard", "Starboard gunner", seatAt(-0.45, BOW_CABIN_Z)),
                maximSeat("port", "Port gunner", seatAt(0.45, BOW_CABIN_Z - 1.0)),
            )

        private fun cabinPlatform(centerZ: Double) = deckFloor(-1.0, 1.0, centerZ - 3.5, centerZ + 3.5, FLOOR, cube = 1.0)

        /** Walkway joining the two cabin platforms, one block above the cabin floors. */
        private fun boardwalk() = deckFloor(-1.0, 1.0, STERN_CABIN_Z + 3.5, BOW_CABIN_Z - 3.5, BOARDWALK_TOP, cube = 1.0)

        /** One unbroken run of overlapping cubes per side, so players slide along it. The ends stay open for the cabins. */
        private fun boardwalkRails(): List<ShulkerHitboxPart> {
            val minZ = STERN_CABIN_Z + 3.5
            val maxZ = BOW_CABIN_Z - 3.5
            return deckFloor(-1.0, -0.5, minZ, maxZ, BOARDWALK_TOP + 0.5, cube = 0.5) +
                deckFloor(0.5, 1.0, minZ, maxZ, BOARDWALK_TOP + 0.5, cube = 0.5)
        }

        private fun cabinRails(centerZ: Double) = railing(-1.0, 1.0, centerZ - 3.5, centerZ + 3.5, FLOOR, cube = 0.25, height = 0.75)

        private fun seatAt(
            x: Double,
            z: Double,
        ) = Vec(x, FLOOR + 0.3, z)
    }

    /** Model "dupuy-de-lome": 1 unit = 0.75 blocks. A 33 block long envelope over a small wooden gondola. */
    private object Dirigible {
        const val SCALE = 12.0
        const val GROUND_CLEARANCE = 0.9

        private const val FLOOR = -11.77
        private const val SEAT_HEIGHT = -11.48

        val hull =
            ellipsoid(Vec(0.0, 7.6, 0.0), Vec(7.5, 7.2, 16.5), slices = 9) +
                HitboxPart(offset = Vec(0.0, -10.9, 0.4), size = Vec(1.95, 3.4, 6.0))

        val platform = deckFloor(-1.425, 1.425, -2.775, 2.775, FLOOR, cube = 0.75)
        val rails = railing(-1.425, 1.425, -2.775, 2.775, FLOOR, cube = 0.25, height = 0.75)

        val seats =
            listOf(
                VehicleSeat("pilot", "Pilot", VehicleSeatRole.DRIVER, Vec(0.0, SEAT_HEIGHT, 1.5)),
                maximSeat("starboard", "Starboard gunner", Vec(-0.825, SEAT_HEIGHT, 0.0)),
            )
    }
}
