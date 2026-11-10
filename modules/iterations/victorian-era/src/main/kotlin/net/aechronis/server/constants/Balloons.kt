package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.CollisionBuilder
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.aechronis.combat.objects.VehicleSeat
import net.aechronis.combat.objects.VehicleSeatRole
import net.aechronis.server.objects.Balloon
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Vec
import kotlin.math.cos
import kotlin.math.sin

object Balloons {
    // The siege envelopes were about 16 m across, which would make this model's basket 4 m wide.
    // The scale follows the crew instead: the basket rim stands 1.2 m above its floor.
    // Raw floor-to-rim height: 5.165033 (-11.522219 to -6.357186). Scale = metres * 16 / raw extent.
    private val ballonMonteScale = Vec(3.717304419934)

    // Model-space heights of the basket's rim and floor.
    private const val RIM_TOP = -6.357186
    private const val FLOOR_TOP = -11.522219

    val ballonMonte =
        Balloon(
            name = "ballon-monte",
            itemName = Component.text("Ballon Monté", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemLore =
                listOf(
                    Component
                        .text("Siege of Paris mail balloon · 1 crew seat", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                    Component.text("Forward to climb · Back to descend", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                ),
            itemModel = "aechronis:parisian-ballons-montes",
            model = "aechronis:parisian-ballons-montes",
            scale = ballonMonteScale.y,
            modelScale = ballonMonteScale,
            // The envelope tiers follow its measured radius. Rigging and the boarding
            // stirrup below the basket do not obstruct, so the basket rests on the ground.
            hitbox =
                Hitbox(
                    listOf(box(4.19489, -12.170728, 4.19489, 11.80511, RIM_TOP, 11.80511)) +
                        envelopeTier(4.1, 8.0, 7.9) +
                        envelopeTier(8.0, 12.0, 11.6) +
                        envelopeTier(12.0, 24.0, 13.4) +
                        envelopeTier(24.0, 29.0, 10.9),
                ),
            health =
                Health(
                    100F,
                    mapOf(
                        AmmoTypes.NORMAL to 2F,
                        AmmoTypes.EXPLOSIVE to 50F,
                        AmmoTypes.BOMB to 100F,
                        AmmoTypes.MISSILE to 100F,
                    ),
                ),
            // The elevatorman sits a block above the floor at the basket's centre, so they can see over the rim
            // whichever way they face. Dismounting drops them onto the floor, not the rim rail, from
            // 0.3 m up: a rider only receives the floor's shulkers on leaving, and clients place them a
            // tick late, so a rider set on the floor would sink into it before it could hold them.
            seat =
                VehicleSeat(
                    "elevatorman",
                    "Elevatorman",
                    VehicleSeatRole.DRIVER,
                    point(8.0, FLOOR_TOP, 8.0).add(0.0, 1.0, 0.0),
                    exitOffset = point(8.0, FLOOR_TOP, 8.0).add(0.0, 0.3, 0.0),
                ),
            collisionHitbox = basketCollision(),
        )

    val all: List<Balloon> = listOf(ballonMonte)

    // Item displays turn the model's -Z toward vehicle-local +Z and mirror X; the model center is (8, 8, 8).
    private fun point(
        x: Double,
        y: Double,
        z: Double,
    ): Vec = Vec((8.0 - x) * ballonMonteScale.x / 16, (y - 8.0) * ballonMonteScale.y / 16, (8.0 - z) * ballonMonteScale.z / 16)

    private fun box(
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
    ): HitboxPart =
        HitboxPart(
            point((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2),
            Vec(maxX - minX, maxY - minY, maxZ - minZ).mul(ballonMonteScale).div(32.0),
        )

    /** Two crossed boxes whose corners lie on the envelope's circular section. */
    private fun envelopeTier(
        minY: Double,
        maxY: Double,
        radius: Double,
    ): List<HitboxPart> {
        val long = radius * cos(Math.toRadians(30.0))
        val short = radius * sin(Math.toRadians(30.0))
        return listOf(
            box(8.0 - long, minY, 8.0 - short, 8.0 + long, maxY, 8.0 + short),
            box(8.0 - short, minY, 8.0 - long, 8.0 + short, maxY, 8.0 + long),
        )
    }

    /** A floor and a rim rail keep standing riders inside the open basket. */
    private fun basketCollision() =
        CollisionBuilder(ballonMonteScale)
            .apply {
                surface(5.129207, 10.870793, 4.862603, 11.137397, FLOOR_TOP, 0.3)
                // The default 0.3 m rail sits on the rim's centreline, its top 1 m above the floor and
                // 0.2 m below the rim, so players can jump in from the ground but not walk out.
                val railY = FLOOR_TOP + 0.85 * 16 / ballonMonteScale.y
                val corners = listOf(4.39 to 4.39, 11.61 to 4.39, 11.61 to 11.61, 4.39 to 11.61)
                (corners + corners.first()).zipWithNext { (fromX, fromZ), (toX, toZ) ->
                    rail(Vec(fromX, railY, fromZ), Vec(toX, railY, toZ))
                }
            }.build()
}
