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

object Balloons {
    // Placeholder model is 16 pixels tall at scale 6.0: envelope above, basket below, origin at the model centre.
    private val envelope = HitboxPart(offset = Vec(0.0, 1.875, 0.0), size = Vec(2.2, 1.125, 2.2))
    private val basket = HitboxPart(offset = Vec(0.0, -2.25, 0.0), size = Vec(1.1, 0.75, 1.1))
    private val hotAirBalloonHitbox = Hitbox(listOf(envelope, basket))
    private val basketFloor =
        (-2..2).flatMap { x -> (-2..2).map { z -> ShulkerHitboxPart(Vec(x * 0.5, -2.75, z * 0.5), 0.5) } }

    val hotAirBalloon =
        Balloon(
            name = "hot-air-balloon",
            itemName = Component.text("Hot Air Balloon", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            scale = 6.0,
            hitbox = hotAirBalloonHitbox,
            // 20 rifle shots
            health =
                Health(
                    20 * RIFLE_HIT,
                    mapOf(
                        AmmoTypes.NORMAL to RIFLE_HIT,
                        AmmoTypes.EXPLOSIVE to 10 * RIFLE_HIT,
                        AmmoTypes.BOMB to 20 * RIFLE_HIT,
                        AmmoTypes.MISSILE to 20 * RIFLE_HIT,
                    ),
                ),
            // holds 10 coal
            maxFuel = 10 * COAL_FUEL,
            // the pilot stands by the burner; up to three more people stand in the basket
            seats = listOf(VehicleSeat("pilot", "Pilot", VehicleSeatRole.DRIVER, Vec(0.6, -2.45, 0.6), standing = true)),
            // solid envelope and a thin basket floor; the basket itself stays open to stand in
            collisionHitbox = ShulkerHitbox(ShulkerHitbox.fromHitbox(Hitbox(listOf(envelope))).parts + basketFloor),
        )
}
