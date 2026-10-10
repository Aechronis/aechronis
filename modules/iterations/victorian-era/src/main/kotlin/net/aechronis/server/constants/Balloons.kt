package net.aechronis.server.constants

import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.aechronis.combat.objects.ShulkerHitbox
import net.aechronis.combat.objects.VehicleSeat
import net.aechronis.combat.objects.VehicleSeatRole
import net.aechronis.server.objects.Balloon
import net.minestom.server.coordinate.Vec

object Balloons {
    // Model "parisian-ballons-montes": 1 model unit = SCALE / 16 blocks, origin at the model centre.
    // Numbers below are blocks relative to that origin. The basket is 8 units wide, the envelope 27.
    private const val SCALE = 5.0
    private const val BASKET_FLOOR = -6.28
    private const val SEAT_HEIGHT = BASKET_FLOOR + 0.5
    private const val SEAT_SPREAD = 0.44

    // Damage hitbox: the envelope in three slices, plus the basket.
    private val skirt = HitboxPart(offset = Vec(0.0, -0.47, 0.0), size = Vec(3.1, 0.78, 3.1))
    private val body = HitboxPart(offset = Vec(0.0, 2.66, 0.0), size = Vec(4.2, 2.35, 4.2))
    private val crown = HitboxPart(offset = Vec(0.0, 5.78, 0.0), size = Vec(2.5, 0.78, 2.5))
    private val basket = HitboxPart(offset = Vec(0.0, -5.5, 0.0), size = Vec(1.25, 1.0, 1.25))

    // Solid envelope plus a thin floor; the basket itself stays open to stand in.
    private val basketFloor = deckFloor(-0.875, 0.875, -0.875, 0.875, BASKET_FLOOR, cube = 0.25)

    // The pilot and a passenger sit in opposite corners of the basket.
    private val seats =
        listOf(
            VehicleSeat("pilot", "Pilot", VehicleSeatRole.DRIVER, Vec(SEAT_SPREAD, SEAT_HEIGHT, SEAT_SPREAD)),
            riderSeat(1, Vec(-SEAT_SPREAD, SEAT_HEIGHT, -SEAT_SPREAD)),
        )

    val hotAirBalloon =
        Balloon(
            name = "hot-air-balloon",
            itemName = vehicleTitle("Hot Air Balloon"),
            itemModel = "aechronis:parisian-ballons-montes",
            model = "aechronis:parisian-ballons-montes",
            scale = SCALE,
            hitbox = Hitbox(listOf(skirt, body, crown, basket)),
            health = vehicleHealth(rifleShots = 20, shells = 2, bombs = 1),
            maxFuel = 10 * COAL_FUEL,
            seats = seats,
            collisionHitbox = ShulkerHitbox(ShulkerHitbox.fromHitbox(Hitbox(listOf(skirt, body, crown))).parts + basketFloor),
        )
}
