package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.aechronis.combat.objects.ShulkerHitbox
import net.aechronis.combat.objects.ShulkerHitboxPart
import net.aechronis.combat.objects.VehicleSeat
import net.aechronis.combat.objects.VehicleSeatRole
import net.aechronis.server.objects.Balloon
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Vec

/** Vehicle damage is flat per hit by ammo type; Victorian rifles do 44-50, so 47 on average. */
internal const val RIFLE_HIT = 47F

/** Ticks of flight one piece of coal buys (45 seconds); matches Balloon.coalFuel. */
internal const val COAL_FUEL = 900

/** Health that dies to [rifleShots] rifle hits, [shells] artillery shells or [bombs] bombs/missiles. */
internal fun vehicleHealth(
    rifleShots: Int,
    shells: Int,
    bombs: Int,
): Health {
    val total = rifleShots * RIFLE_HIT
    return Health(
        total,
        mapOf(
            AmmoTypes.NORMAL to RIFLE_HIT,
            AmmoTypes.EXPLOSIVE to total / shells,
            AmmoTypes.BOMB to total / bombs,
            AmmoTypes.MISSILE to total / bombs,
        ),
    )
}

object Balloons {
    // Model "parisian-ballons-montes": 1 model unit = SCALE / 16 blocks, origin at the model centre.
    // Numbers below are blocks relative to that origin. The basket is 8 units wide, the envelope 27.
    private const val SCALE = 5.0
    private const val FLOOR_TOP = -4.8

    private val skirt = HitboxPart(offset = Vec(0.0, -0.47, 0.0), size = Vec(3.1, 0.78, 3.1))
    private val body = HitboxPart(offset = Vec(0.0, 2.66, 0.0), size = Vec(4.2, 2.35, 4.2))
    private val crown = HitboxPart(offset = Vec(0.0, 5.78, 0.0), size = Vec(2.5, 0.78, 2.5))
    private val basket = HitboxPart(offset = Vec(0.0, -5.5, 0.0), size = Vec(1.25, 1.0, 1.25))

    // Solid envelope plus a thin floor; the basket itself stays open to stand in.
    private val basketFloor =
        (-4..4).flatMap { x -> (-4..4).map { z -> ShulkerHitboxPart(Vec(x * 0.25, FLOOR_TOP - 0.125, z * 0.25), 0.25) } }

    val hotAirBalloon =
        Balloon(
            name = "hot-air-balloon",
            itemName = Component.text("Hot Air Balloon", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemModel = "aechronis:parisian-ballons-montes",
            model = "aechronis:parisian-ballons-montes",
            scale = SCALE,
            hitbox = Hitbox(listOf(skirt, body, crown, basket)),
            health = vehicleHealth(rifleShots = 20, shells = 2, bombs = 1),
            // holds 10 coal
            maxFuel = 10 * COAL_FUEL,
            // the pilot sits in the basket corner; up to three more people stand beside them
            seats = listOf(VehicleSeat("pilot", "Pilot", VehicleSeatRole.DRIVER, Vec(0.7, FLOOR_TOP + 0.3, 0.7))),
            collisionHitbox = ShulkerHitbox(ShulkerHitbox.fromHitbox(Hitbox(listOf(skirt, body, crown))).parts + basketFloor),
        )
}
